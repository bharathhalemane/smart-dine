package com.example.qrorder.media.model;

import com.example.qrorder.tenancy.model.Permission;

/** What an image is for. Decides who may upload it and where it is stored. Add LOGO etc. here later. */
public enum MediaPurpose {
    MENU_ITEM(Permission.MANAGE_MENU, "menu");

    private final Permission permission;
    private final String folder;

    MediaPurpose(Permission permission, String folder) {
        this.permission = permission;
        this.folder = folder;
    }

    public Permission permission() {
        return permission;
    }

    public String folder() {
        return folder;
    }
}