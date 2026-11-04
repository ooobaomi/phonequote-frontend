package com.local.phonequote;

public interface TeamApi {
    void getMyTeam(ApiCallback<TeamInfo> callback);

    void createTeam(String name, ApiCallback<TeamInfo> callback);

    void updateTeam(String name, Boolean allowMemberUploads, ApiCallback<TeamInfo> callback);

    void addTeamMember(String phone, ApiCallback<TeamInfo> callback);

    void removeTeamMember(String userId, ApiCallback<TeamInfo> callback);
}
