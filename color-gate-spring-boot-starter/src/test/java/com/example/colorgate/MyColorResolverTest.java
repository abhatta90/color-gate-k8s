package com.example.colorgate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MyColorResolverTest {

    @Test
    void colorComesFromTheNamespace() {
        assertThat(MyColorResolver.resolve(null, "blue")).isEqualTo(Color.BLUE);
        assertThat(MyColorResolver.resolve("", "green")).isEqualTo(Color.GREEN);
        assertThat(MyColorResolver.resolve(null, "tier1-green")).isEqualTo(Color.GREEN);
    }

    @Test
    void colorComesFromAppColorWhenTheNamespaceIsNotAColor() {
        assertThat(MyColorResolver.resolve("green", "default")).isEqualTo(Color.GREEN);
    }

    @Test
    void appColorAndNamespaceMustAgree() {
        assertThatThrownBy(() -> MyColorResolver.resolve("GREEN", "blue"))
                .hasMessageContaining("says GREEN").hasMessageContaining("namespace 'blue'");
    }

    @Test
    void invalidAppColorFailsStartup() {
        assertThatThrownBy(() -> MyColorResolver.resolve("RED", "blue")).hasMessageContaining("'RED'");
    }

    @Test
    void unknownColorFailsStartup() {
        assertThatThrownBy(() -> MyColorResolver.resolve(null, "default"))
                .hasMessageContaining("cannot tell this pod's color");
    }
}
