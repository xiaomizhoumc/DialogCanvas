package dialogcanvas.engine.action;

import dialogcanvas.model.MenuJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NumericExpressionTest {
    @Test void arithmeticPrecedenceAndTypedReference() {
        assertEquals(205, NumericExpression.evaluate("(100 * $(amount)) + 10 / 2", Map.of("amount", MenuJson.createMapper().readTree("2"))));
        assertEquals(-.001, NumericExpression.evaluate("-1e-3", Map.of()));
    }
    @ParameterizedTest @ValueSource(strings = {"1 / 0", "NaN", "1e309", "2 * 1e308", "$(missing)", "Runtime.exec(1)", "0x10", "1;2", ""})
    void invalidExpressionsFailClosed(String expression) {
        assertThrows(IllegalArgumentException.class, () -> NumericExpression.evaluate(expression, Map.of()));
    }
}
