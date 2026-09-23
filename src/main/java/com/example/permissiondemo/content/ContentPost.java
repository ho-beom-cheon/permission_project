package com.example.permissiondemo.content;

import java.time.Instant;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** ID와 버전은 JavaScript의 정수 정밀도를 잃지 않도록 문자열로 전달한다. */
public record ContentPost(
        @JsonSerialize(using = ToStringSerializer.class) long id,
        String type, String title, String body, String status, String author,
        Instant createdAt, Instant updatedAt,
        @JsonSerialize(using = ToStringSerializer.class) long version) {}
