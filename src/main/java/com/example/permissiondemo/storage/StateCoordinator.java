package com.example.permissiondemo.storage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 여러 도메인의 변경·감사 기록을 원자적으로 영속화하며 저장 실패 시 실행 상태도 되돌린다.
 * 동일 프로세스의 조회는 작업 단위 밖에서 미완료 변경을 볼 수 없다.
 */
@Aspect
@Component
@Order(0)
public class StateCoordinator implements SmartInitializingSingleton {
    private final ApplicationContext context;
    private final ComponentStateRepository repository;
    private final ObjectMapper json;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Map<String, StateParticipant> participants = new LinkedHashMap<>();
    private boolean ready;
    private long revision;

    public StateCoordinator(ApplicationContext context, ComponentStateRepository repository, ObjectMapper json) {
        this.context = context;
        this.repository = repository;
        this.json = json;
    }

    @Override
    public void afterSingletonsInstantiated() {
        context.getBeansOfType(StateParticipant.class).values().forEach(participant -> {
            if (participants.putIfAbsent(participant.stateKey(), participant) != null) {
                throw new IllegalStateException("중복 저장 모델: " + participant.stateKey());
            }
        });
        Map<String, String> persisted = repository.load();
        revision = repository.revision();
        persisted.forEach((key, payload) -> {
            StateParticipant participant = participants.get(key);
            if (participant == null) throw new IllegalStateException("저장 모델을 찾을 수 없습니다: " + key);
            try {
                participant.restoreState(json.readValue(payload, participant.stateType()));
            } catch (Exception exception) {
                throw new IllegalStateException("저장 상태를 복원할 수 없습니다: " + key, exception);
            }
        });
        Map<String, String> initial = new LinkedHashMap<>();
        snapshots().forEach((key, state) -> { if (!persisted.containsKey(key)) initial.put(key, state.toString()); });
        if (!initial.isEmpty()) { repository.commit(revision++, initial); }
        ready = true;
    }

    @Around("execution(public * com.example.permissiondemo..*(..)) && @within(com.example.permissiondemo.storage.StateBoundary)"
            + " && !execution(* *.stateKey(..)) && !execution(* *.stateType(..))"
            + " && !execution(* *.snapshotState(..)) && !execution(* *.restoreState(..))")
    public Object around(ProceedingJoinPoint invocation) throws Throwable {
        if (!ready || lock.isHeldByCurrentThread()) return invocation.proceed();
        lock.lock();
        Map<String, JsonNode> before = null;
        long oldRevision = revision;
        try {
            before = snapshots();
            Map<String, JsonNode> old = before;
            return repository.inTransaction(() -> {
                Object result;
                try { result = invocation.proceed(); }
                catch (Throwable failure) { throw new InvocationFailure(failure); }
                Map<String, String> changes = new LinkedHashMap<>();
                snapshots().forEach((key, state) -> { if (!state.equals(old.get(key))) changes.put(key, state.toString()); });
                if (!changes.isEmpty()) { repository.commit(revision, changes); revision++; }
                return result;
            });
        } catch (Throwable failure) {
            revision = oldRevision;
            if (before != null) {
                for (var entry : before.entrySet()) {
                    StateParticipant participant = participants.get(entry.getKey());
                    participant.restoreState(json.treeToValue(entry.getValue(), participant.stateType()));
                }
            }
            throw failure instanceof InvocationFailure wrapped ? wrapped.getCause() : failure;
        } finally {
            lock.unlock();
        }
    }

    private static final class InvocationFailure extends RuntimeException {
        InvocationFailure(Throwable cause) { super(cause); }
    }

    private Map<String, JsonNode> snapshots() {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        participants.forEach((key, participant) -> result.put(key, json.valueToTree(participant.snapshotState())));
        return result;
    }
}
