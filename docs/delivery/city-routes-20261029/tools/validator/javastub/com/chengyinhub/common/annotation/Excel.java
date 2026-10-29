package com.chengyinhub.common.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
public @interface Excel { String name() default ""; String dateFormat() default ""; String readConverterExp() default ""; double width() default 16; }
