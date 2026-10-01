package com.schwab.urlshortener.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * The structural guardrails of the redirect controller (D70, D75, D76, D79). The D75 encoder is tested in
 * {@code LocationEncoderTest}.
 */
class RedirectControllerTest {

    // ---- D70: never produces, never inherited

    @Test
    void shouldNeverDeclareProducesOnTheClassOrTheMethodOrInheritOne() throws Exception {
        Method redirect = RedirectController.class.getDeclaredMethod("redirect", String.class, HttpMethod.class);

        RequestMapping onMethod = AnnotatedElementUtils.findMergedAnnotation(redirect, RequestMapping.class);
        assertThat(onMethod).isNotNull();
        assertThat(onMethod.produces()).isEmpty();
        assertThat(onMethod.path()).containsExactly("/{code}");
        assertThat(onMethod.method()).containsExactly(RequestMethod.GET);

        assertThat(AnnotatedElementUtils.findMergedAnnotation(RedirectController.class, RequestMapping.class))
                .as("no class-level @RequestMapping, so nothing to inherit produces from").isNull();
        assertThat(RedirectController.class.getSuperclass()).isEqualTo(Object.class);
        assertThat(RedirectController.class.getInterfaces()).isEmpty();
    }

    @Test
    void shouldCarryNoMetaAnnotationThatDeclaresProduces() throws Exception {
        Method redirect = RedirectController.class.getDeclaredMethod("redirect", String.class, HttpMethod.class);

        long withProduces = Stream.concat(
                        MergedAnnotations.from(RedirectController.class).stream(),
                        MergedAnnotations.from(redirect).stream())
                .filter(a -> a.getType().getName().startsWith("org.springframework.web.bind.annotation."))
                .filter(a -> Arrays.stream(a.getType().getDeclaredMethods())
                        .anyMatch(m -> m.getName().equals("produces")))
                .filter(a -> a.getStringArray("produces").length > 0)
                .count();

        assertThat(withProduces).isZero();
    }

    // ---- Location and redirect API guardrails (D75, D79)

    @Test
    void shouldTakeOnlyTheCodeAndTheHttpMethodAndReturnAResponseEntitySoTheQueryStringIsNeverReachable()
            throws Exception {
        Method redirect = RedirectController.class.getDeclaredMethod("redirect", String.class, HttpMethod.class);

        // The code and the HTTP method (D9): no HttpServletRequest, no RedirectAttributes, no @RequestParam.
        assertThat(redirect.getParameterTypes()).containsExactly(String.class, HttpMethod.class);
        assertThat(redirect.getParameterCount()).isEqualTo(2);
        assertThat(redirect.getParameters()[0].getAnnotations()).hasSize(1);
        assertThat(redirect.getParameters()[0].getAnnotations()[0])
                .isInstanceOf(PathVariable.class);
        assertThat(redirect.getParameters()[1].getAnnotations()).isEmpty();
        assertThat(redirect.getReturnType()).isEqualTo(ResponseEntity.class);
    }

    /**
     * Secondary guard only: the reflection test above is the primary proof. This scans the source for API names
     * that would re-derive or re-route the stored Location string (D75, D79).
     */
    @Test
    void shouldNotUseAnyLocationRewritingApiInTheControllerSource() throws Exception {
        String baseDir = System.getProperty("basedir", System.getProperty("user.dir"));
        Path file = Path.of(baseDir, "src/main/java/com/schwab/urlshortener/controller/RedirectController.java");
        assertThat(file).exists();
        String code = Files.readString(file).lines()
                .filter(l -> !l.trim().startsWith("*") && !l.trim().startsWith("//") && !l.trim().startsWith("/**"))
                .collect(Collectors.joining("\n"));

        assertThat(code).contains(".header(HttpHeaders.LOCATION, LocationEncoder.encode(");
        assertThat(code).doesNotContain("java.net.URI").doesNotContain(".location(").doesNotContain("setLocation")
                .doesNotContain("sendRedirect").doesNotContain("\"redirect:").doesNotContain("RedirectView")
                .doesNotContain("RedirectAttributes").doesNotContain("toASCIIString")
                .doesNotContain("HttpServletRequest");
    }
}
