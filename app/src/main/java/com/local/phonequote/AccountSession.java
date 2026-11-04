package com.local.phonequote;

public final class AccountSession {
    public final String accountId;
    public final String name;
    public final String displayName;
    public final String phone;
    public final String accessToken;
    public final String refreshToken;
    public final int accessExpiresInSeconds;
    public final long expiresAtMillis;
    public final boolean mustChangePassword;
    public final boolean profileCompleted;
    public final String accountType;
    public final String teamId;
    public final String teamName;
    public final String teamRole;
    public final String teamStatus;
    public final int teamVersion;
    public final int quoteCatalogVersion;
    public final boolean canUploadQuotes;

    public AccountSession(
            String accountId,
            String displayName,
            String phone,
            String accessToken,
            String refreshToken,
            int accessExpiresInSeconds,
            long expiresAtMillis,
            boolean mustChangePassword
    ) {
        this(accountId, displayName, displayName, phone, accessToken, refreshToken,
                accessExpiresInSeconds, expiresAtMillis, mustChangePassword,
                true, "PERSONAL", "", "", "", "", 0, 1, true);
    }

    public AccountSession(
            String accountId,
            String name,
            String displayName,
            String phone,
            String accessToken,
            String refreshToken,
            int accessExpiresInSeconds,
            long expiresAtMillis,
            boolean mustChangePassword,
            boolean profileCompleted,
            String accountType,
            String teamId,
            String teamName,
            String teamRole,
            String teamStatus,
            int teamVersion,
            int quoteCatalogVersion,
            boolean canUploadQuotes
    ) {
        this.accountId = accountId;
        this.name = name;
        this.displayName = displayName;
        this.phone = phone;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.accessExpiresInSeconds = accessExpiresInSeconds;
        this.expiresAtMillis = expiresAtMillis;
        this.mustChangePassword = mustChangePassword;
        this.profileCompleted = profileCompleted;
        this.accountType = accountType;
        this.teamId = teamId;
        this.teamName = teamName;
        this.teamRole = teamRole;
        this.teamStatus = teamStatus;
        this.teamVersion = teamVersion;
        this.quoteCatalogVersion = quoteCatalogVersion;
        this.canUploadQuotes = canUploadQuotes;
    }
}
