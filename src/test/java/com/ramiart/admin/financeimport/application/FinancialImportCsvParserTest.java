package com.ramiart.admin.financeimport.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FinancialImportCsvParserTest {
    @Test void parsesCrLfQuotesCommasAndEmbeddedLineBreaks(){
        var rows=FinancialImportCsvParser.parse("date,description\r\n2026-10-01,\"재료, 물감\"\r\n2026-10-02,\"첫 줄\n둘째 줄\"");
        assertThat(rows).containsExactly(java.util.List.of("date","description"),java.util.List.of("2026-10-01","재료, 물감"),java.util.List.of("2026-10-02","첫 줄\n둘째 줄"));
    }
    @Test void rejectsUnclosedQuotesAndTextAfterQuotedField(){
        assertThatThrownBy(()->FinancialImportCsvParser.parse("a,b\n1,\"broken")).isInstanceOf(FinancialImportCsvParser.CsvFormatException.class);
        assertThatThrownBy(()->FinancialImportCsvParser.parse("a,b\n1,\"ok\"tail")).isInstanceOf(FinancialImportCsvParser.CsvFormatException.class);
    }
}
