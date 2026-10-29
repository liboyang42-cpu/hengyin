package org.springframework.transaction.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD, ElementType.TYPE})
public @interface Transactional { Propagation propagation() default Propagation.REQUIRED; Class<? extends Throwable>[] rollbackFor() default {}; boolean readOnly() default false; }
