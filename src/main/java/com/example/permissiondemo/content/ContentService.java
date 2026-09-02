package com.example.permissiondemo.content;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.ProgramAuthorizationService;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import com.example.permissiondemo.security.CurrentUserContext;
import com.example.permissiondemo.storage.StateBoundary;
import com.example.permissiondemo.storage.StateParticipant;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.stereotype.Service;

/** 공지·FAQ·Q&A·자료실·약관의 작성, 게시기간, 비공개 접근, 답변, 첨부, 동의 이력을 관리한다. */
@Service
@StateBoundary
public class ContentService implements StateParticipant {
    private final Map<Long, Post> posts = new TreeMap<>();
    private final Map<String, Agreement> agreements = new TreeMap<>();
    private final Map<Long,Long> views = new TreeMap<>();
    private long sequence;
    private final CurrentUserContext current;
    private final ProgramAuthorizationService authorization;
    private final AuditEventService audit;
    private final AttachmentRepository attachments;
    private final Clock clock;
    private final BoardService boards;
    private final org.springframework.beans.factory.ObjectProvider<AttachmentUse> attachmentUses;
    public ContentService(CurrentUserContext current, ProgramAuthorizationService authorization, AuditEventService audit, AttachmentRepository attachments, Clock clock, org.springframework.beans.factory.ObjectProvider<AttachmentUse> attachmentUses, BoardService boards) {
        this.current=current; this.authorization=authorization; this.audit=audit; this.attachments=attachments; this.clock=clock;
        this.attachmentUses=attachmentUses; this.boards=boards;
    }
    public PageResult<Post> list(Board board, String query, PageQuery page) {
        return list(board.name(),query,page);
    }
    public PageResult<Post> list(String boardId, String query, PageQuery page) {
        String actor=current.require().username(); boolean editor=canPublish(); LocalDate today=LocalDate.now(clock);
        if(!boards.definition(boardId).active()&&!editor)throw new ApiException(ErrorCode.ACCESS_DENIED);
        String search=query==null?"":query.toLowerCase(Locale.ROOT);
        return PageResult.of(posts.values().stream().filter(post->post.boardId().equals(boardId) && visible(post,actor,editor,today))
                .filter(post->post.title().toLowerCase(Locale.ROOT).contains(search)||post.body().toLowerCase(Locale.ROOT).contains(search))
                .sorted(Comparator.comparing(Post::pinned).reversed().thenComparing(Comparator.comparing(Post::createdAt).reversed())).toList(),page);
    }
    public Post get(long id) {
        Post post=requirePost(id);
        if(!visible(post,current.require().username(),canPublish(),LocalDate.now(clock)))throw new ApiException(ErrorCode.ACCESS_DENIED);
        return post;
    }
    public PageResult<Post> noticeFeed(String query,PageQuery page){
        String actor=current.require().username(),search=query==null?"":query.toLowerCase(Locale.ROOT);boolean editor=canPublish();LocalDate today=LocalDate.now(clock);
        return PageResult.of(posts.values().stream().filter(p->p.board()==Board.NOTICE&&boards.definition(p.boardId()).active()&&visible(p,actor,editor,today))
                .filter(p->p.title().toLowerCase(Locale.ROOT).contains(search)||p.body().toLowerCase(Locale.ROOT).contains(search))
                .sorted(Comparator.comparing(Post::pinned).reversed().thenComparing(Comparator.comparing(Post::createdAt).reversed())).toList(),page);
    }
    public Post save(long id, WritePost request) {
        String actor=current.require().username(); boolean editor=canPublish();
        if(request.board()==null||request.title()==null||request.title().isBlank()||request.title().length()>200
                ||request.body()==null||request.body().isBlank()||request.body().length()>100000)throw new IllegalArgumentException("게시판·제목·내용을 확인해 주세요.");
        String boardId=request.boardId()==null?request.board().name():request.boardId();
        BoardService.Definition board=boards.definition(boardId);
        if(board.type()!=request.board()||!board.active())throw new ApiException(ErrorCode.CONFLICT,"게시판 유형 또는 사용 상태가 변경됐습니다.");
        if(request.board()!=Board.QNA&&!canWrite())throw new ApiException(ErrorCode.ACCESS_DENIED);
        if(request.board()!=Board.QNA&&request.published()&&!editor)throw new ApiException(ErrorCode.ACCESS_DENIED);
        if(request.pinned()&&!editor)throw new ApiException(ErrorCode.ACCESS_DENIED);
        if(request.pinned()&&!board.noticeEnabled())throw new ApiException(ErrorCode.CONFLICT,"상단 공지를 사용하지 않는 게시판입니다.");
        if(request.startDate()!=null&&request.endDate()!=null&&request.endDate().isBefore(request.startDate()))throw new IllegalArgumentException("게시 종료일이 시작일보다 빠릅니다.");
        Post previous=id==0?null:requirePost(id);
        if(previous!=null) {
            if(previous.deleted()||(!previous.author().equals(actor)&&!editor))throw new ApiException(ErrorCode.ACCESS_DENIED);
            if(previous.board()!=request.board()||!previous.boardId().equals(boardId)||previous.version()!=request.expectedVersion())throw new ApiException(ErrorCode.CONFLICT,id);
            if(previous.board()==Board.TERMS&&previous.published())throw new ApiException(ErrorCode.CONFLICT,"게시된 약관은 새 버전으로 등록해 주세요.");
        } else if(request.expectedVersion()!=0)throw new ApiException(ErrorCode.CONFLICT,id);
        String label=request.versionLabel()==null?"":request.versionLabel().trim();
        if(label.length()>50||(request.board()==Board.TERMS&&label.isBlank()))throw new IllegalArgumentException("약관 버전은 1~50자로 입력해 주세요.");
        if(request.board()==Board.TERMS&&posts.values().stream().anyMatch(p->p.id()!=id&&p.boardId().equals(boardId)&&p.versionLabel().equals(label)))throw new ApiException(ErrorCode.CONFLICT,"이미 등록된 약관 버전입니다.");
        List<String> ids=request.attachmentIds()==null?List.of():request.attachmentIds().stream().distinct().toList();
        if(ids.size()>board.maxAttachments())throw new IllegalArgumentException("이 게시판의 첨부파일은 "+board.maxAttachments()+"개 이하여야 합니다.");
        for(String file:ids){
            var metadata=attachments.metadata(file).orElseThrow(()->new ApiException(ErrorCode.RESOURCE_NOT_FOUND,file));
            if(!metadata.owner().equals(actor)&&(previous==null||!previous.attachmentIds().contains(file)))throw new ApiException(ErrorCode.ACCESS_DENIED);
        }
        Instant now=Instant.now(clock);long postId=previous==null?++sequence:id;
        Post saved=new Post(postId,request.board(),request.title().trim(),request.body(),previous==null?actor:previous.author(),
                previous==null?now:previous.createdAt(),now,request.expectedVersion()+1,request.published(),request.publicRead(),request.pinned(),
                request.startDate(),request.endDate(),label,ids,previous==null?"":previous.answer(),previous==null?null:previous.answeredBy(),false,boardId);
        posts.put(postId,saved);audit.record("POST_SAVED",request.board().name(),String.valueOf(postId),"SUCCESS",Map.of("version",saved.version()));return saved;
    }
    public Post answer(long id,String answer,long version){
        if(!canPublish())throw new ApiException(ErrorCode.ACCESS_DENIED);Post old=requirePost(id);
        if(old.board()!=Board.QNA||old.deleted()||old.version()!=version)throw new ApiException(ErrorCode.CONFLICT,id);
        if(!boards.definition(old.boardId()).answerEnabled()||!boards.definition(old.boardId()).active())throw new ApiException(ErrorCode.CONFLICT,"답변을 사용할 수 없는 게시판입니다.");
        if(answer==null||answer.isBlank()||answer.length()>100000)throw new IllegalArgumentException("답변을 입력해 주세요.");
        Post updated=new Post(old.id(),old.board(),old.title(),old.body(),old.author(),old.createdAt(),Instant.now(clock),old.version()+1,
                old.published(),old.publicRead(),old.pinned(),old.startDate(),old.endDate(),old.versionLabel(),old.attachmentIds(),answer,current.require().username(),false,old.boardId());
        posts.put(id,updated);audit.record("QNA_ANSWERED","POST",String.valueOf(id),"SUCCESS",Map.of());return updated;
    }
    public void delete(long id,long version){
        Post old=requirePost(id);String actor=current.require().username();
        if(!canPublish()&&!(old.board()==Board.QNA&&old.author().equals(actor)))throw new ApiException(ErrorCode.ACCESS_DENIED);
        if(old.version()!=version||old.deleted())throw new ApiException(ErrorCode.CONFLICT,id);
        if(old.board()==Board.TERMS&&old.published())throw new ApiException(ErrorCode.CONFLICT,"게시된 약관은 동의 이력 보존을 위해 삭제할 수 없습니다.");
        posts.put(id,new Post(old.id(),old.board(),old.title(),old.body(),old.author(),old.createdAt(),Instant.now(clock),old.version()+1,
                old.published(),old.publicRead(),old.pinned(),old.startDate(),old.endDate(),old.versionLabel(),old.attachmentIds(),old.answer(),old.answeredBy(),true,old.boardId()));
        audit.record("POST_DELETED","POST",String.valueOf(id),"SUCCESS",Map.of());
    }
    public AttachmentRepository.Metadata upload(String name,byte[] data){
        if(data==null||data.length==0||data.length>10*1024*1024)throw new IllegalArgumentException("첨부파일은 1바이트~10MB까지 등록할 수 있습니다.");
        String clean=name==null?"attachment":name.replaceAll("[\\\\/\\r\\n]","_");
        if(clean.length()>200)throw new IllegalArgumentException("파일명은 200자 이하여야 합니다.");
        var metadata=new AttachmentRepository.Metadata(UUID.randomUUID().toString(),current.require().username(),clean,data.length,Instant.now(clock));
        attachments.save(metadata,data);audit.record("FILE_UPLOADED","ATTACHMENT",metadata.id(),"SUCCESS",Map.of("size",data.length));return metadata;
    }
    public Download download(String id){
        var metadata=attachments.metadata(id).orElseThrow(()->new ApiException(ErrorCode.RESOURCE_NOT_FOUND,id));
        String actor=current.require().username();boolean editor=canPublish();
        List<Post> linked=posts.values().stream().filter(post->post.attachmentIds().contains(id)).toList();
        List<AttachmentUse> uses=attachmentUses.stream().filter(use->use.isLinked(id)).toList();
        boolean allowed=linked.isEmpty()&&uses.isEmpty()?metadata.owner().equals(actor)
                :linked.stream().anyMatch(post->visible(post,actor,editor,LocalDate.now(clock)))||uses.stream().anyMatch(use->use.canReadAttachment(id));
        if(!allowed)throw new ApiException(ErrorCode.ACCESS_DENIED);
        audit.record("FILE_DOWNLOADED","ATTACHMENT",id,"SUCCESS",Map.of());return new Download(metadata,attachments.content(id));
    }
    public void deleteUnlinkedUpload(String id){
        var metadata=attachments.metadata(id).orElseThrow(()->new ApiException(ErrorCode.RESOURCE_NOT_FOUND,id));
        if(!metadata.owner().equals(current.require().username()))throw new ApiException(ErrorCode.ACCESS_DENIED);
        if(posts.values().stream().anyMatch(post->post.attachmentIds().contains(id))||attachmentUses.stream().anyMatch(use->use.isLinked(id)))throw new ApiException(ErrorCode.CONFLICT,"업무에 연결된 첨부파일입니다.");
        attachments.delete(id);audit.record("FILE_DELETED","ATTACHMENT",id,"SUCCESS",Map.of());
    }
    public Agreement agree(long id){
        Post post=get(id);LocalDate today=LocalDate.now(clock);
        if(post.board()!=Board.TERMS||!post.published()||!post.publicRead()||!boards.definition(post.boardId()).active()
                ||post.startDate()!=null&&today.isBefore(post.startDate())||post.endDate()!=null&&today.isAfter(post.endDate()))throw new ApiException(ErrorCode.CONFLICT,id);
        String actor=current.require().username();String key=actor+":"+id;
        Agreement result=agreements.computeIfAbsent(key,ignored->new Agreement(actor,id,post.versionLabel(),Instant.now(clock)));
        audit.record("TERMS_AGREED","TERMS",String.valueOf(id),"SUCCESS",Map.of());return result;
    }
    public List<Agreement> myAgreements(){String actor=current.require().username();return agreements.values().stream().filter(a->a.username().equals(actor)).toList();}
    public long recordView(long id){get(id);long count=views.merge(id,1L,Long::sum);audit.record("POST_VIEWED","POST",String.valueOf(id),"SUCCESS",Map.of());return count;}
    public long views(long id){get(id);return views.getOrDefault(id,0L);}
    private boolean visible(Post post,String actor,boolean editor,LocalDate today){
        if(post.deleted())return false;if(editor)return true;if(!boards.definition(post.boardId()).active())return false;if(post.author().equals(actor))return true;
        return post.published()&&post.publicRead()&&(post.startDate()==null||!today.isBefore(post.startDate()))&&(post.endDate()==null||!today.isAfter(post.endDate()));
    }
    private Post requirePost(long id){Post post=posts.get(id);if(post==null)throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND,id);return post;}
    private boolean canWrite(){return authorization.isAllowed(current.authentication(),"CONTENT_LIST","CONTENT","CONTENT_SAVE");}
    private boolean canPublish(){return authorization.isAllowed(current.authentication(),"CONTENT_LIST","CONTENT","CONTENT_PUBLISH");}
    public enum Board { NOTICE, FAQ, QNA, DOCUMENT, TERMS }
    public record WritePost(Board board,String title,String body,boolean published,boolean publicRead,boolean pinned,LocalDate startDate,LocalDate endDate,String versionLabel,List<String> attachmentIds,long expectedVersion,String boardId) {
        public WritePost(Board board,String title,String body,boolean published,boolean publicRead,boolean pinned,LocalDate startDate,LocalDate endDate,String versionLabel,List<String> attachmentIds,long expectedVersion){this(board,title,body,published,publicRead,pinned,startDate,endDate,versionLabel,attachmentIds,expectedVersion,null);}
    }
    public record Post(long id,Board board,String title,String body,String author,Instant createdAt,Instant updatedAt,long version,boolean published,boolean publicRead,boolean pinned,
            LocalDate startDate,LocalDate endDate,String versionLabel,List<String> attachmentIds,String answer,String answeredBy,boolean deleted,String boardId) {
        public Post{if(boardId==null)boardId=board.name();}
    }
    public record Agreement(String username,long postId,String versionLabel,Instant agreedAt) { }
    public record Download(AttachmentRepository.Metadata metadata,byte[] data) { }
    @Override public String stateKey(){return "content";}
    @Override public Class<?> stateType(){return StoredState.class;}
    @Override public Object snapshotState(){return new StoredState(List.copyOf(posts.values()),Map.copyOf(agreements),sequence,Map.copyOf(views));}
    @Override public void restoreState(Object raw){StoredState state=(StoredState)raw;posts.clear();state.posts().forEach(p->posts.put(p.id(),p));agreements.clear();agreements.putAll(state.agreements());sequence=state.sequence();views.clear();if(state.views()!=null)views.putAll(state.views());}
    public record StoredState(List<Post> posts,Map<String,Agreement> agreements,long sequence,Map<Long,Long> views) { }
}
