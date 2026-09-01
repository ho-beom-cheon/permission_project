package com.example.permissiondemo.web;

import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/** 현재 요청 Locale과 messages.properties를 사용해 오류 코드를 사용자 메시지로 변환한다. */
@Component
public class MessageResolver {

    private final MessageSource messageSource;

    public MessageResolver(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** 메시지 키, 치환 인자와 기본 메시지를 적용해 최종 표시 문구를 반환한다. */
    public String resolve(ErrorCode errorCode, Object... args) {
        Locale locale = LocaleContextHolder.getLocale();
        return messageSource.getMessage(
                errorCode.messageKey(), args, errorCode.defaultMessage(), locale);
    }
}
