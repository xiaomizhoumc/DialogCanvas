package dialogcanvas.dialog;

import dialogcanvas.model.AfterAction;
import dialogcanvas.model.DialogType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestMenuServiceTest {
    @TempDir Path directory;

    @Test
    void shopAndMenuExamplesLoadAndRetainLocalEdits() throws Exception {
        try (var shop = new TestMenuService(directory, TestMenuService.Example.SHOP);
             var main = new TestMenuService(directory, TestMenuService.Example.MENU)) {
            shop.initialize(); main.initialize();
            var shopMenu = shop.load().join();
            assertEquals(TestMenuService.SHOP_MENU_ID, shopMenu.getId());
            assertEquals(DialogType.MULTI_ACTION, shopMenu.getDialogType());
            assertEquals(java.util.List.of("number_range", "bool", "text"),
                    shopMenu.getInputs().stream().map(dialogcanvas.model.InputDefinition::getType).toList());
            var item = assertInstanceOf(dialogcanvas.model.BodyBlock.Item.class, shopMenu.getBody().get(1));
            assertEquals("DIAMOND_SWORD", item.getMaterial());
            assertTrue(item.getGlow()); assertNotNull(item.getDescription());
            for (var button : shopMenu.getActions()) {
                assertTrue(button.getActions() == null || button.getActions().isEmpty());
                assertEquals("custom", button.getActionType());
            }
            var menu = main.load().join();
            assertEquals(TestMenuService.MAIN_MENU_ID, menu.getId());
            assertEquals(2, menu.getColumns()); assertEquals(4, menu.getActions().size());
            assertEquals("close", menu.getExitAction().getActionType());
            String originalShop = Files.readString(shop.file());
            String edited = Files.readString(main.file()).replace("简单菜单测试", "修改后的测试菜单");
            Files.writeString(main.file(), edited); shop.initialize(); main.initialize();
            assertEquals(edited, Files.readString(main.file()));
            assertEquals(originalShop, Files.readString(shop.file()));
        }
    }

    @Test
    void copiesBundledMenuAndPreservesUserEditsOnRestart() throws Exception {
        try (var examples = new TestMenuService(directory)) {
            examples.initialize();
            var menu = examples.load().join();
            assertEquals(DialogType.NOTICE, menu.getDialogType());
            assertEquals(AfterAction.NONE, menu.getAfterAction());
            assertEquals("close", menu.getNoticeAction().getActionType());
            assertEquals("test_close", menu.getNoticeAction().getId());
            String edited = Files.readString(examples.file()).replace("DCMenu 真机测试", "Edited title");
            Files.writeString(examples.file(), edited);
            examples.initialize();
            assertEquals(edited, Files.readString(examples.file()));
            assertEquals("&6Edited title", examples.load().join().getTitle().getValue().asString());
        }
    }

    @Test
    void reportsMalformedJsonAndClosedExecutorThroughFuture() throws Exception {
        var examples = new TestMenuService(directory);
        try {
            examples.initialize();
            Files.writeString(examples.file(), "{invalid");
            assertThrows(java.util.concurrent.CompletionException.class, () -> examples.load().join());
        } finally {
            examples.close();
        }
        assertTrue(examples.load().isCompletedExceptionally());
    }

    @Test
    void iconDemoIsSeparateMultiColumnMenuAndPreservesEdits() throws Exception {
        try (var minimal = new TestMenuService(directory);
             var icons = new TestMenuService(directory, TestMenuService.Example.ICONS)) {
            minimal.initialize(); String oldMenu = Files.readString(minimal.file());
            icons.initialize(); var menu = icons.load().join();
            assertEquals(TestMenuService.ICON_MENU_ID, menu.getId()); assertEquals(DialogType.MULTI_ACTION, menu.getDialogType());
            assertEquals(2, menu.getColumns()); assertEquals(6, menu.getActions().size());
            assertEquals(java.util.List.of("BONE", "ENDER_PEARL", "BLAZE_ROD", "DIAMOND_SWORD", "STONE", "DCMENU_INVALID_ITEM"),
                    menu.getActions().stream().map(button -> button.getIcon().getMaterial()).toList());
            String edited = Files.readString(icons.file()).replace("&f骨头", "&f修改后的骨头");
            Files.writeString(icons.file(), edited); icons.initialize();
            assertEquals(edited, Files.readString(icons.file())); assertEquals(oldMenu, Files.readString(minimal.file()));
        }
    }
}
