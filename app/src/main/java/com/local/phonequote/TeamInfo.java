package com.local.phonequote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TeamInfo {
    public final String accountType;
    public final boolean canCreateTeam;
    public final String id;
    public final String name;
    public final String status;
    public final int memberLimit;
    public final int memberCount;
    public final boolean allowMemberUploads;
    public final int version;
    public final String ownerId;
    public final String ownerName;
    public final int sharedTableCount;
    public final List<Member> members;

    public TeamInfo(
            String accountType,
            boolean canCreateTeam,
            String id,
            String name,
            String status,
            int memberLimit,
            int memberCount,
            boolean allowMemberUploads,
            int version,
            String ownerId,
            String ownerName,
            int sharedTableCount,
            List<Member> members
    ) {
        this.accountType = accountType;
        this.canCreateTeam = canCreateTeam;
        this.id = id;
        this.name = name;
        this.status = status;
        this.memberLimit = memberLimit;
        this.memberCount = memberCount;
        this.allowMemberUploads = allowMemberUploads;
        this.version = version;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.sharedTableCount = sharedTableCount;
        this.members = Collections.unmodifiableList(new ArrayList<>(members));
    }

    public boolean hasTeam() {
        return id != null && !id.isEmpty();
    }

    public static final class Member {
        public final String userId;
        public final String name;
        public final String phone;
        public final String role;
        public final String status;
        public final long joinedAtMillis;

        public Member(String userId, String name, String phone, String role, String status, long joinedAtMillis) {
            this.userId = userId;
            this.name = name;
            this.phone = phone;
            this.role = role;
            this.status = status;
            this.joinedAtMillis = joinedAtMillis;
        }
    }
}
