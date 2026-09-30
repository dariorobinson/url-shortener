package com.schwab.urlshortener.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guards what {@code NoTransactionalAnnotationIT} cannot see: annotations on repository interface methods (the proxy
 * target there is {@code SimpleJpaRepository}). Plain reflection, no Spring context. The click UPDATE is pinned exactly
 * (D16, D27, D91) and no repository may carry a transaction boundary of its own, because the click recorder's
 * {@code REQUIRES_NEW} template must own the one transaction that covers both the counter and the event.
 */
class RepositoryAnnotationsTest {

    private static final String EXPECTED_CLICK_SQL = "UPDATE short_url SET click_count = click_count + 1,"
            + " last_accessed_at = :clickedAt WHERE id = :id AND status = 'ACTIVE'";

    private static List<Class<?>> repositoryInterfaces() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isInterface();
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));
        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.schwab.urlshortener")) {
            try {
                found.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return found;
    }

    /** Merged annotations also catch composed and meta-annotations. */
    private static boolean isTransactional(AnnotatedElement element) {
        MergedAnnotations merged = MergedAnnotations.from(element, SearchStrategy.TYPE_HIERARCHY);
        return merged.isPresent(Transactional.class)
                || merged.isPresent(jakarta.transaction.Transactional.class);
    }

    @Test
    void shouldFindExactlyTheTwoRepositoryInterfaces() {
        Set<String> names = new TreeSet<>();
        repositoryInterfaces().forEach(c -> names.add(c.getSimpleName()));

        assertThat(names).containsExactly("ClickEventRepository", "ShortUrlRepository");
    }

    @Test
    void shouldDeclareTheClickUpdateExactlyAsApproved() throws Exception {
        Method recordClick = ShortUrlRepository.class.getDeclaredMethod("recordClick", long.class, Instant.class);

        assertThat(recordClick.getReturnType()).isEqualTo(int.class);
        Modifying modifying = recordClick.getAnnotation(Modifying.class);
        assertThat(modifying).isNotNull();
        assertThat(modifying.flushAutomatically()).isTrue();
        assertThat(modifying.clearAutomatically()).isTrue();
        Query query = recordClick.getAnnotation(Query.class);
        assertThat(query).isNotNull();
        assertThat(query.nativeQuery()).isTrue();
        assertThat(query.value()).isEqualTo(EXPECTED_CLICK_SQL);
        assertThat(ShortUrlRepository.RECORD_CLICK_SQL).isEqualTo(EXPECTED_CLICK_SQL);
    }

    @Test
    void shouldCarryNoTransactionalAnnotationOnAnyRepositoryInterfaceOrDeclaredMethod() {
        List<Class<?>> repositories = repositoryInterfaces();
        assertThat(repositories).hasSize(2);
        List<String> offenders = new ArrayList<>();
        int methodsInspected = 0;
        for (Class<?> repository : repositories) {
            if (isTransactional(repository)) {
                offenders.add(repository.getSimpleName());
            }
            for (Method method : repository.getDeclaredMethods()) {
                methodsInspected++;
                if (isTransactional(method)) {
                    offenders.add(repository.getSimpleName() + "#" + method.getName());
                }
            }
        }

        assertThat(methodsInspected).as("non-vacuous: recordClick and findByShortCode were inspected")
                .isGreaterThanOrEqualTo(2);
        assertThat(offenders).isEmpty();
    }

    @Test
    void shouldHaveNoModifyingMethodOtherThanRecordClickAndNoLock() {
        List<String> modifying = new ArrayList<>();
        List<String> locks = new ArrayList<>();
        for (Class<?> repository : repositoryInterfaces()) {
            for (Method method : repository.getDeclaredMethods()) {
                if (MergedAnnotations.from(method, SearchStrategy.TYPE_HIERARCHY).isPresent(Modifying.class)) {
                    modifying.add(repository.getSimpleName() + "#" + method.getName());
                }
                if (MergedAnnotations.from(method, SearchStrategy.TYPE_HIERARCHY).isPresent(Lock.class)) {
                    locks.add(repository.getSimpleName() + "#" + method.getName());
                }
            }
        }

        assertThat(modifying).containsExactly("ShortUrlRepository#recordClick");
        assertThat(locks).isEmpty();
    }
}
