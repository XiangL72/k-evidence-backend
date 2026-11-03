package com.kreview.kreview.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.kreview.kreview.document.SourceDocument;
import org.junit.jupiter.api.Test;

class CompositionMatrixBuilderTests {
  private final CompositionMatrixBuilder builder = new CompositionMatrixBuilder();

  @Test void explicitBatchChangeIsScopedAndUnmentionedTransportIsNotAChange() {
    var a = new SourceDocument("a", "第三条 交货\n3.1 第一批：2026年4月30日；第三批：2026年6月30日。\n3.2 公路运输。\n");
    var b = new SourceDocument("b", "第三条 交货期调整\n3.1 主合同第3.1条约定的第三批，由2026年6月30日顺延至2026年7月20日。\n");
    var matrix = builder.build(a, b, "SUPPLEMENT");
    var change = matrix.rows().stream().filter(r -> r.key().equals("DELIVERY"))
        .flatMap(r -> r.supplement().stream()).findFirst().orElseThrow();
    assertThat(change.relation()).isEqualTo("CHANGED");
    assertThat(change.oldValue()).isEqualTo("2026年6月30日");
    assertThat(change.newValue()).isEqualTo("2026年7月20日");
    assertThat(change.targetSources()).hasSize(1);
    assertThat(matrix.rows().stream().flatMap(r -> r.supplement().stream())
        .noneMatch(e -> e.summary().contains("公路运输"))).isTrue();
  }

  @Test void missingTargetStaysInFixedTableWithNoteAndExactEvidence() {
    var a = new SourceDocument("a", "第一条 货物\n1.1 电机10台。\n");
    var b = new SourceDocument("b", "第二条 技术参数\n2.1 追加电机精度由17位调整为20位。\n");
    var matrix = builder.build(a, b, "SUPPLEMENT");
    assertThat(matrix.rows()).hasSize(11);
    var item = matrix.rows().stream().filter(r -> r.key().equals("GOODS"))
        .flatMap(r -> r.supplement().stream()).findFirst().orElseThrow();
    assertThat(item.relation()).isEqualTo("REVIEW");
    assertThat(item.note()).contains("待核对");
    item.sources().getFirst().validateAgainst(b);
  }

  @Test void wrongOldDateAndWrongPaymentSumStayReviewableInTheirCategories() {
    var a = new SourceDocument("a", "第三条 交货\n3.1 第三批：2026年6月30日。\n");
    var b = new SourceDocument("b", "1.1 主合同第3.1条第三批由2026年6月29日顺延至2026年7月20日。\n"
        + "2.1 追加部分金额合计：￥100.00。\n"
        + "2.2 追加货物的货款按以下方式支付：￥30.00、￥60.00、￥5.00。\n");
    var matrix = builder.build(a, b, "SUPPLEMENT");
    var all = matrix.rows().stream().flatMap(r -> r.supplement().stream()).toList();
    assertThat(all.stream().filter(e -> e.clauseKey().equals("1.1")).findFirst().orElseThrow().relation())
        .isEqualTo("REVIEW");
    assertThat(all.stream().filter(e -> e.clauseKey().equals("2.2")).findFirst().orElseThrow().relation())
        .isEqualTo("REVIEW");
    assertThat(matrix.rows().stream().filter(r -> r.key().equals("DELIVERY"))
        .flatMap(r -> r.supplement().stream())).hasSize(1);
    assertThat(matrix.rows().stream().filter(r -> r.key().equals("PAYMENT"))
        .flatMap(r -> r.supplement().stream())).hasSize(1);
  }

}
