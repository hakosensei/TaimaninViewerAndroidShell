package com.example.xunleivrplayer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Compile-time marker used only to avoid toolchain annotation resolution quirks. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
@interface Override {}
