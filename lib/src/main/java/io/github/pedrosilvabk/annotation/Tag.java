package io.github.pedrosilvabk.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.CLASS)
public @interface Tag {
    int value() default -1 ;
    boolean useInnerTagAsParent() default false;
    boolean presenceIsValue() default false;

    Class codec() default void.class ;
}
