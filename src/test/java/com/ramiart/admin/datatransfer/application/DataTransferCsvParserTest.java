package com.ramiart.admin.datatransfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DataTransferCsvParserTest {
    @Test
    void parsesEscapedQuotesAndMultilineFieldsWithoutChangingCellValues() {
        var rows = DataTransferCsvParser.parse("name,note\r\n\"김가람\",\"첫 줄\n두 번째 줄, \"\"좋아요\"\"\"\r\n");

        assertThat(rows).containsExactly(
                java.util.List.of("name", "note"),
                java.util.List.of("김가람", "첫 줄\n두 번째 줄, \"좋아요\""));
    }

    @Test
    void rejectsUnclosedQuotesAndMoreThanTenThousandDataRows() {
        assertThatThrownBy(() -> DataTransferCsvParser.parse("name,note\n\"open,note"))
                .isInstanceOf(DataTransferCsvParser.CsvFormatException.class);
        String tooManyRows = "name\n" + "value\n".repeat(10_001);
        assertThatThrownBy(() -> DataTransferCsvParser.parse(tooManyRows))
                .isInstanceOf(DataTransferCsvParser.CsvFormatException.class);
    }
}
