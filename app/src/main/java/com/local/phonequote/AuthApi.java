package com.local.phonequote;

public interface AuthApi {
    void sendRegisterCode(String phone, ApiCallback<Integer> callback);

    void register(String name, String phone, String password, String code, ApiCallback<AccountSession> callback);

    void login(String account, String password, ApiCallback<AccountSession> callback);

    void getCurrentSession(ApiCallback<AccountSession> callback);

    void logout(ApiCallback<Boolean> callback);

    void changePassword(String currentPassword, String newPassword, ApiCallback<Boolean> callback);

    void updateProfile(String name, ApiCallback<AccountSession> callback);

    void getSupport(ApiCallback<SupportInfo> callback);
}
