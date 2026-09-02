package com.example.permissiondemo.storage;

/** 도메인별 버전이 있는 저장 모델. 런타임 캐시와 인증 토큰은 저장하지 않는다. */
public interface StateParticipant {
    String stateKey();
    Class<?> stateType();
    Object snapshotState();
    void restoreState(Object state);
}
