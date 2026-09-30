package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * The service owns its transactions through {@code TransactionTemplate}s, so no application bean may carry
 * {@code @Transactional} (Spring's or Jakarta's) on its class or on any declared method: an annotation would
 * silently nest or replace the template's propagation. This complements the unit-level reflection guard by
 * scanning the beans of the running context.
 */
class NoTransactionalAnnotationIT extends IntegrationTestBase {

    private static final String APP_PACKAGE = "com.schwab.urlshortener";

    @Autowired
    private ApplicationContext context;

    @Test
    void shouldHaveNoTransactionalAnnotationOnAnyApplicationBeanClassOrMethod() {
        Set<String> scanned = new TreeSet<>();
        List<String> offenders = new ArrayList<>();
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            if (type == null) {
                continue;
            }
            Class<?> target = AopUtils.getTargetClass(context.getBean(name));
            if (!target.getName().startsWith(APP_PACKAGE)) {
                continue;
            }
            scanned.add(target.getSimpleName());
            if (isTransactional(target.getAnnotations())) {
                offenders.add(target.getName());
            }
            for (Method method : target.getDeclaredMethods()) {
                if (isTransactional(method.getAnnotations())) {
                    offenders.add(target.getName() + "#" + method.getName());
                }
            }
        }

        // Non-vacuous: the beans that own transactions were in the scanned set.
        assertThat(scanned).contains("ShortUrlService", "RedirectService", "ShortUrlController",
                "JpaClickRecorder");
        assertThat(offenders).isEmpty();
    }

    private static boolean isTransactional(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            String name = annotation.annotationType().getName();
            if ("org.springframework.transaction.annotation.Transactional".equals(name)
                    || "jakarta.transaction.Transactional".equals(name)) {
                return true;
            }
        }
        return false;
    }
}
