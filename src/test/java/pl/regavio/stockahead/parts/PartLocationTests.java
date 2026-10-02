package pl.regavio.stockahead.parts;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PartLocationTests {

	@Test
	void normalizeStripsZeroWidthCharactersAtBothEnds() {
		assertThat(PartLocation.normalize("A1​")).isEqualTo("A1");
		assertThat(PartLocation.normalize("﻿A1")).isEqualTo("A1");
		assertThat(PartLocation.normalize("​﻿ A1 ​\t ")).isEqualTo("A1");
	}

	@Test
	void normalizeKeepsZeroWidthCharactersInside() {
		assertThat(PartLocation.normalize(" A​1 ")).isEqualTo("A​1");
	}

	@Test
	void zeroWidthPastedLocationIsTheSameShelfIgnoringCase() {
		assertThat(PartLocation.sameLocation("a1​", "A1")).isTrue();
		assertThat(PartLocation.sameLocation("﻿Regał a1 ", "Regał A1")).isTrue();
	}

}
