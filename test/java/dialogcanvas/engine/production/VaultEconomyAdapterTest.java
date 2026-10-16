package dialogcanvas.engine.production;
import net.milkbowl.vault.economy.*;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VaultEconomyAdapterTest {
    double balance; boolean throwsAfterChange, rejects, misreports;
    Set<UUID> uncertain; VaultEconomyAdapter adapter;
    @BeforeEach void setup() {
        var server = MockBukkit.mock(); balance = 1000; uncertain = new HashSet<>(); adapter = new VaultEconomyAdapter(uncertain::add);
        Economy economy = new Economy() {
            public String getName() { return "verified fixture"; }
            public int fractionalDigits() { return 2; }
            public double getBalance(OfflinePlayer player) { assertTrue(Bukkit.isPrimaryThread()); return balance; }
            public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) { return apply(-amount); }
            public EconomyResponse depositPlayer(OfflinePlayer player, double amount) { return apply(amount); }
            private EconomyResponse apply(double delta) { assertTrue(Bukkit.isPrimaryThread()); if (rejects) return new EconomyResponse(false); balance += delta;
                if (throwsAfterChange) throw new IllegalStateException("Provider crashed after mutation"); return new EconomyResponse(!misreports); }
        };
        server.getServicesManager().register(Economy.class, economy, MockBukkit.createMockPlugin(), ServicePriority.Normal);
    }
    @AfterEach void cleanup() { MockBukkit.unmock(); }
    @Test void actualDeltaCompensationAndIdempotentRefund() {
        UUID id = UUID.randomUUID(); assertEquals("verified fixture", adapter.providerName());
        var receipt = adapter.withdraw(id, 200).join(); assertEquals(800, balance); receipt.rollback().join(); receipt.rollback().join(); assertEquals(1000, balance); assertTrue(uncertain.isEmpty());
        var credit = adapter.deposit(id, 20).join(); assertEquals(1020, balance); credit.rollback().join(); assertEquals(1000, balance);
    }
    @Test void safeRejectionAndInvalidPrecisionDoNotMutate() {
        UUID id = UUID.randomUUID(); assertThrows(Exception.class, () -> adapter.withdraw(id, 2000).join());
        assertThrows(Exception.class, () -> adapter.withdraw(id, 0.001).join()); rejects = true;
        assertThrows(Exception.class, () -> adapter.withdraw(id, 10).join()); assertEquals(1000, balance); assertTrue(uncertain.isEmpty());
    }
    @Test void insufficientFundsHasTypedReasonAndNoMutation() {
        var failure = assertThrows(java.util.concurrent.CompletionException.class, () -> adapter.withdraw(UUID.randomUUID(), 1001).join());
        assertInstanceOf(dialogcanvas.engine.action.InsufficientFundsException.class, failure.getCause());
        assertEquals(1000, balance); assertTrue(uncertain.isEmpty());
    }
    @Test void ambiguousMutationBlocksPlayerInsteadOfPretendingRollback() {
        UUID id = UUID.randomUUID(); throwsAfterChange = true; assertThrows(Exception.class, () -> adapter.withdraw(id, 10).join());
        assertEquals(990, balance); assertTrue(uncertain.contains(id));
    }
    @Test void providerResponseMustMatchRealBalance() {
        UUID id = UUID.randomUUID(); misreports = true; assertThrows(Exception.class, () -> adapter.withdraw(id, 10).join()); assertTrue(uncertain.contains(id));
    }
}
