package dialogcanvas.model;


/** Exactly one of menuId and registryKey must be supplied by the validator. */
public final class DialogReference extends ModelPojo {
    private String menuId;

    public String getMenuId() {
        return menuId;
    }

    public void setMenuId(String menuId) {
        this.menuId = menuId;
    }

    private String registryKey;

    public String getRegistryKey() {
        return registryKey;
    }

    public void setRegistryKey(String registryKey) {
        this.registryKey = registryKey;
    }
}
