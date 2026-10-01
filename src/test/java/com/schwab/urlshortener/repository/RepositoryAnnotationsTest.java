package com.schwab.urlshortener.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guards what {@code NoTransactionalAnnotationIT} cannot see: annotations on repository interface methods (the proxy
 * target there is {@code SimpleJpaRepository}). Plain reflection, no Spring context. The click UPDATE is pinned exactly
 * (D16, D27, D91) and no repository may carry a transaction boundary of its own, because the click recorder's
 * {@code REQUIRES_NEW} template must own the one transaction that covers both the counter and the event.
 */
class RepositoryAnnotationsTest {

    private static final String EXPECTED_CLICK_SQL = "UPDATE short_url SET click_count = click_count + 1,"
            + " last_accessed_at = GREATEST(last_accessed_at, :clickedAt) WHERE id = :id AND status = 'ACTIVE'"
            + " AND (expires_at IS NULL OR expires_at > :clickedAt)";

    /** D95, D103: the stats SQL, pinned. No zone string, no AT TIME ZONE: PostgreSQL only counts. */
    private static final String EXPECTED_STATS_SQL = "SELECT width_bucket(clicked_at,"
            + " CAST(string_to_array(:dayStarts, ',') AS timestamptz[])) AS day_index,"
            + " count(*) AS clicks FROM click_event"
            + " WHERE short_url_id = :shortUrlId AND clicked_at >= :rangeStart AND clicked_at < :rangeEnd"
            + " GROUP BY day_index ORDER BY day_index";

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
    void shouldDeclareTheStatsQueryExactlyAsApprovedWithNoZoneParameter() throws Exception {
        Method rows = ClickEventRepository.class.getDeclaredMethod("countClicksPerDayRows", long.class, Instant.class,
                Instant.class, String.class);

        assertThat(rows.getReturnType()).isEqualTo(List.class);
        Query query = rows.getAnnotation(Query.class);
        assertThat(query).isNotNull();
        assertThat(query.nativeQuery()).isTrue();
        assertThat(query.value()).isEqualTo(EXPECTED_STATS_SQL);
        assertThat(ClickEventRepository.CLICKS_PER_DAY_SQL).isEqualTo(EXPECTED_STATS_SQL);
        assertThat(MergedAnnotations.from(rows, SearchStrategy.TYPE_HIERARCHY).isPresent(Modifying.class)).isFalse();
        assertThat(rows.getParameters()).extracting(p -> p.getAnnotation(Param.class).value())
                .containsExactly("shortUrlId", "rangeStart", "rangeEnd", "dayStarts");
    }

    @Test
    void shouldKeepAnyTimeZoneConversionOutOfEveryRepositorySql() {
        String statsSql = ClickEventRepository.CLICKS_PER_DAY_SQL.toLowerCase(Locale.ROOT);
        String clickSql = ShortUrlRepository.RECORD_CLICK_SQL.toLowerCase(Locale.ROOT);

        assertThat(statsSql).doesNotContain("at time zone").doesNotContain("timezone(").doesNotContain(":zone")
                .doesNotContain(":timezone").doesNotContain("::");
        assertThat(clickSql).doesNotContain("at time zone").doesNotContain("timezone(");
        // Positive control for the detector: the lower-casing and the substring checks do see these tokens.
        assertThat("SELECT x AT TIME ZONE :zone".toLowerCase(Locale.ROOT)).contains("at time zone").contains(":zone");
        // The stats predicate compares the bare column (sargable, D103).
        assertThat(statsSql).contains("clicked_at >= :rangestart and clicked_at < :rangeend");
    }

    /**
     * The detector behind the no-{@code @Transactional} guard below must see a direct, a composed and a Jakarta
     * annotation, or that guard would pass vacuously (the service owns its transactions, D102).
     */
    @Test
    void shouldDetectDirectAndComposedTransactionalAnnotations() throws Exception {
        Method direct = DirectlyAnnotated.class.getDeclaredMethod("save");
        Method composed = ComposedAnnotated.class.getDeclaredMethod("save");
        Method jakarta = JakartaAnnotated.class.getDeclaredMethod("save");
        Method plain = PlainSample.class.getDeclaredMethod("save");

        assertThat(isTransactional(direct)).isTrue();
        assertThat(isTransactional(composed)).isTrue();
        assertThat(isTransactional(jakarta)).isTrue();
        assertThat(isTransactional(DirectlyAnnotated.class)).isFalse();
        assertThat(isTransactional(plain)).isFalse();
        assertThat(isTransactional(PlainSample.class)).isFalse();
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @Transactional
    @interface ComposedTransactional {
    }

    interface DirectlyAnnotated {
        @Transactional
        void save();
    }

    interface ComposedAnnotated {
        @ComposedTransactional
        void save();
    }

    interface JakartaAnnotated {
        @jakarta.transaction.Transactional
        void save();
    }

    interface PlainSample {
        void save();
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

        assertThat(methodsInspected).as("non-vacuous: recordClick, findByShortCode and the stats methods")
                .isGreaterThanOrEqualTo(4);
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
