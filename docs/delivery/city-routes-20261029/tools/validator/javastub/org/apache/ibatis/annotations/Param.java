package org.apache.ibatis.annotations;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER) public @interface Param { String value(); }
