package com.example.permissiondemo.storage;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 여러 업무 저장소를 읽거나 변경하는 호출을 하나의 일관된 작업 단위로 묶는다. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface StateBoundary { }
