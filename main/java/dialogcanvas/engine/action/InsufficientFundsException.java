package dialogcanvas.engine.action;

/** Known rejection before withdrawal; distinct from provider failure or an uncertain mutation. */
public final class InsufficientFundsException extends IllegalStateException {
    public InsufficientFundsException() { super("余额不足，未扣款"); }
}
