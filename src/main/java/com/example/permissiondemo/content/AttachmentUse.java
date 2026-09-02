package com.example.permissiondemo.content;

/** 첨부파일을 사용하는 업무가 연결 여부와 읽기 권한을 제공한다. */
public interface AttachmentUse {
    boolean isLinked(String attachmentId);
    boolean canReadAttachment(String attachmentId);
}
