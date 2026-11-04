package com.local.phonequote;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class AccountFragment extends Fragment {
    private TextView accountName;
    private TextView accountMeta;
    private TextView accountExpiry;
    private EditText profileNameInput;
    private LinearLayout teamSection;
    private TextView teamSummary;
    private EditText teamNameInput;
    private Switch allowUploadsSwitch;
    private Button saveTeamButton;
    private LinearLayout addMemberPanel;
    private EditText memberPhoneInput;
    private LinearLayout memberList;
    private AccountSession session;
    private TeamInfo team;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        View view = inflater.inflate(R.layout.fragment_account, container, false);
        accountName = view.findViewById(R.id.accountName);
        accountMeta = view.findViewById(R.id.accountMeta);
        accountExpiry = view.findViewById(R.id.accountExpiry);
        profileNameInput = view.findViewById(R.id.profileNameInput);
        teamSection = view.findViewById(R.id.teamSection);
        teamSummary = view.findViewById(R.id.teamSummary);
        teamNameInput = view.findViewById(R.id.teamNameInput);
        allowUploadsSwitch = view.findViewById(R.id.allowMemberUploadsSwitch);
        saveTeamButton = view.findViewById(R.id.saveTeamButton);
        addMemberPanel = view.findViewById(R.id.addMemberPanel);
        memberPhoneInput = view.findViewById(R.id.memberPhoneInput);
        memberList = view.findViewById(R.id.memberList);
        view.findViewById(R.id.saveProfileButton).setOnClickListener(value -> saveProfile());
        view.findViewById(R.id.changePasswordButton).setOnClickListener(value ->
                startActivity(new Intent(requireContext(), ChangePasswordActivity.class)));
        view.findViewById(R.id.logoutButton).setOnClickListener(value -> logout());
        saveTeamButton.setOnClickListener(value -> saveOrCreateTeam());
        view.findViewById(R.id.addMemberButton).setOnClickListener(value -> addMember());
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        loadAccount();
    }

    private void loadAccount() {
        AppServices.auth(requireContext()).getCurrentSession(new ApiCallback<AccountSession>() {
            @Override
            public void onSuccess(AccountSession value) {
                if (!isAdded()) return;
                session = value;
                renderAccount();
                if ("PERSONAL".equals(value.accountType)) {
                    teamSection.setVisibility(View.GONE);
                } else {
                    loadTeam();
                }
            }

            @Override
            public void onError(String message) {
                ((MainActivity) requireActivity()).openLogin();
            }
        });
    }

    private void renderAccount() {
        accountName.setText(session.name == null || session.name.isEmpty() ? "待补全姓名" : session.name);
        accountMeta.setText("账号：" + session.phone + " · " + accountTypeLabel(session.accountType));
        profileNameInput.setText(session.name);
        long remaining = Math.max(0L, session.expiresAtMillis - System.currentTimeMillis());
        long days = remaining / 86400000L;
        long hours = (remaining / 3600000L) % 24L;
        String expiry = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .format(new Date(session.expiresAtMillis));
        accountExpiry.setText("剩余 " + days + " 天 " + hours + " 小时\n到期时间：" + expiry
                + ("TEAM_MEMBER".equals(session.accountType) ? "\n有效期跟随团队主账号" : ""));
    }

    private void saveProfile() {
        String name = profileNameInput.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), "姓名不能为空", Toast.LENGTH_LONG).show();
            return;
        }
        AppServices.auth(requireContext()).updateProfile(name, new ApiCallback<AccountSession>() {
            @Override
            public void onSuccess(AccountSession value) {
                session = value;
                renderAccount();
                Toast.makeText(requireContext(), "姓名已保存", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void loadTeam() {
        AppServices.teams(requireContext()).getMyTeam(new ApiCallback<TeamInfo>() {
            @Override
            public void onSuccess(TeamInfo value) {
                if (!isAdded()) return;
                team = value;
                renderTeam();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void renderTeam() {
        teamSection.setVisibility(View.VISIBLE);
        boolean pending = team.canCreateTeam && !team.hasTeam();
        boolean owner = "TEAM_OWNER".equals(team.accountType);
        if (pending) {
            teamSummary.setText("团队功能已开通，请先创建团队名称。默认总人数包含主账号。 ");
            teamNameInput.setText("");
            teamNameInput.setEnabled(true);
            allowUploadsSwitch.setVisibility(View.GONE);
            saveTeamButton.setText("创建团队");
            addMemberPanel.setVisibility(View.GONE);
            memberList.removeAllViews();
            return;
        }
        teamSummary.setText(team.name + " · " + team.memberCount + "/" + team.memberLimit + " 人 · "
                + ("ACTIVE".equals(team.status) ? "正常使用" : "团队已暂停")
                + "\n共享报价表 " + team.sharedTableCount + " 份");
        teamNameInput.setText(team.name);
        teamNameInput.setEnabled(owner);
        allowUploadsSwitch.setVisibility(owner ? View.VISIBLE : View.GONE);
        allowUploadsSwitch.setChecked(team.allowMemberUploads);
        saveTeamButton.setVisibility(owner ? View.VISIBLE : View.GONE);
        saveTeamButton.setText("保存团队设置");
        addMemberPanel.setVisibility(owner ? View.VISIBLE : View.GONE);
        renderMembers(owner);
    }

    private void renderMembers(boolean owner) {
        memberList.removeAllViews();
        for (TeamInfo.Member member : team.members) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundResource(R.drawable.bg_info_banner);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.bottomMargin = dp(8);
            row.setLayoutParams(params);
            TextView name = new TextView(requireContext());
            name.setText(member.name + ("OWNER".equals(member.role) ? " · 主账号" : " · 子账号"));
            name.setTextColor(requireContext().getColor(R.color.ink));
            name.setTextSize(15f);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            row.addView(name);
            TextView meta = new TextView(requireContext());
            meta.setText(member.phone + " · " + ("ACTIVE".equals(member.status) ? "正常" : "已停用"));
            meta.setTextColor(requireContext().getColor(R.color.muted));
            meta.setTextSize(13f);
            meta.setPadding(0, dp(4), 0, 0);
            row.addView(meta);
            if (owner && "MEMBER".equals(member.role)) {
                Button remove = new Button(requireContext());
                remove.setText("移出团队");
                remove.setAllCaps(false);
                remove.setTextColor(requireContext().getColor(R.color.danger));
                remove.setBackgroundResource(R.drawable.bg_button_secondary);
                remove.setOnClickListener(value -> confirmRemove(member));
                row.addView(remove);
            }
            memberList.addView(row);
        }
    }

    private void saveOrCreateTeam() {
        String name = teamNameInput.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(requireContext(), "请填写团队名称", Toast.LENGTH_LONG).show();
            return;
        }
        ApiCallback<TeamInfo> callback = teamCallback("团队设置已保存");
        if (team != null && team.canCreateTeam && !team.hasTeam()) {
            AppServices.teams(requireContext()).createTeam(name, callback);
        } else {
            AppServices.teams(requireContext()).updateTeam(name, allowUploadsSwitch.isChecked(), callback);
        }
    }

    private void addMember() {
        String phone = memberPhoneInput.getText().toString().trim();
        if (!phone.matches("^1[3-9]\\d{9}$")) {
            Toast.makeText(requireContext(), "请输入已注册的 11 位手机号", Toast.LENGTH_LONG).show();
            return;
        }
        AppServices.teams(requireContext()).addTeamMember(phone, teamCallback("成员已加入团队"));
    }

    private void confirmRemove(TeamInfo.Member member) {
        new AlertDialog.Builder(requireContext())
                .setTitle("移出团队")
                .setMessage("移出 " + member.name + "？其私人报价保留，共享报价会立即转回私人。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认移出", (dialog, which) ->
                        AppServices.teams(requireContext()).removeTeamMember(
                                member.userId, teamCallback("成员已移出团队")
                        ))
                .show();
    }

    private ApiCallback<TeamInfo> teamCallback(String message) {
        return new ApiCallback<TeamInfo>() {
            @Override
            public void onSuccess(TeamInfo value) {
                team = value;
                memberPhoneInput.setText("");
                renderTeam();
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                loadAccount();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
            }
        };
    }

    private void logout() {
        AppServices.auth(requireContext()).logout(new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean value) {
                ((MainActivity) requireActivity()).stopAssistant();
                ((MainActivity) requireActivity()).openLogin();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private String accountTypeLabel(String value) {
        if ("TEAM_OWNER_PENDING".equals(value)) return "团队功能待创建";
        if ("TEAM_OWNER".equals(value)) return "团队主账号";
        if ("TEAM_MEMBER".equals(value)) return "团队子账号";
        return "个人账号";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
