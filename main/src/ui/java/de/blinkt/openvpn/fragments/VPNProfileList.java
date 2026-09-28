/*
 * Copyright (c) 2012-2019 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.fragments;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.ListFragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.text.Html;
import android.text.Html.ImageGetter;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeSet;

import de.blinkt.openvpn.LaunchVPN;
import de.blinkt.openvpn.R;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.activities.ConfigConverter;
import de.blinkt.openvpn.activities.DisconnectVPN;
import de.blinkt.openvpn.activities.FileSelect;
import de.blinkt.openvpn.activities.VPNPreferences;
import de.blinkt.openvpn.core.ConnectionStatus;
import de.blinkt.openvpn.core.PasswordDialogFragment;
import de.blinkt.openvpn.core.Preferences;
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.core.VpnStatus;

import static de.blinkt.openvpn.core.ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT;
import static de.blinkt.openvpn.core.OpenVPNService.DISCONNECT_VPN;
import static de.blinkt.openvpn.core.OpenVPNService.EXTRA_CHALLENGE_TXT;


public class VPNProfileList extends ListFragment implements OnClickListener, VpnStatus.StateListener {

    public final static int RESULT_VPN_DELETED = Activity.RESULT_FIRST_USER;
    public final static int RESULT_VPN_DUPLICATE = Activity.RESULT_FIRST_USER + 1;
    // Shortcut version is increased to refresh all shortcuts
    final static int SHORTCUT_VERSION = 1;
    private static final int MENU_ADD_PROFILE = Menu.FIRST;
    private static final int START_VPN_CONFIG = 92;
    private static final int SELECT_PROFILE = 43;
    private static final int IMPORT_PROFILE = 231;
    private static final int FILE_PICKER_RESULT_KITKAT = 392;
    private static final int MENU_IMPORT_PROFILE = Menu.FIRST + 1;
    private static final int MENU_CHANGE_SORTING = Menu.FIRST + 2;
    private static final int MENU_IMPORT_AS = Menu.FIRST + 3;
    private static final String PREF_SORT_BY_LRU = "sortProfilesByLRU";
    protected VpnProfile mEditProfile = null;
    private String mLastStatusMessage;
    private ArrayAdapter<VpnProfile> mArrayadapter;
    private Intent mLastIntent;
    private VpnProfile defaultVPN;

    // ===== 节点延迟探测(自添加) =====
    private static final ConcurrentHashMap<String, String> sLatencyMap = new ConcurrentHashMap<>();
    private static volatile long sLastProbeTime = 0;
    private static final long PROBE_TTL_MS = 10 * 60 * 1000L;
    private static volatile boolean sProbing = false;
    private static ExecutorService sProbePool = null;
    private SwipeRefreshLayout mSwipeRefresh;

    @Override
    public void updateState(String state, String logmessage, final int localizedResId, ConnectionStatus level, Intent intent) {
        requireActivity().runOnUiThread(() -> {
            mLastStatusMessage = VpnStatus.getLastCleanLogMessage(getActivity());
            mLastIntent = intent;
            mArrayadapter.notifyDataSetChanged();
            showUserRequestDialogIfNeeded(level, intent);
        });
    }

    private boolean showUserRequestDialogIfNeeded(ConnectionStatus level, Intent intent) {
        if (level == LEVEL_WAITING_FOR_USER_INPUT) {
            if (intent != null && intent.getStringExtra(EXTRA_CHALLENGE_TXT) != null) {
                PasswordDialogFragment pwInputFrag = PasswordDialogFragment.Companion.newInstance(intent, false);

                pwInputFrag.show(getParentFragmentManager(), "dialog");
                return true;
            }
        }
        return false;
    }

    @Override
    public void setConnectedVPN(String uuid) {
    }

    private void startOrStopVPN(VpnProfile profile) {
        if (VpnStatus.isVPNActive() && profile.getUUIDString().equals(VpnStatus.getLastConnectedVPNProfile())) {
            if (mLastIntent != null) {
                startActivity(mLastIntent);
            } else {
                Intent disconnectVPN = new Intent(getActivity(), DisconnectVPN.class);
                startActivity(disconnectVPN);
            }
        } else {
            startVPN(profile);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setListAdapter();
    }

    @RequiresApi(api = Build.VERSION_CODES.N_MR1)
    void updateDynamicShortcuts() {
        PersistableBundle versionExtras = new PersistableBundle();
        versionExtras.putInt("version", SHORTCUT_VERSION);

        ShortcutManager shortcutManager = getContext().getSystemService(ShortcutManager.class);
        if (shortcutManager.isRateLimitingActive())
            return;

        List<ShortcutInfo> shortcuts = shortcutManager.getDynamicShortcuts();
        int maxvpn = shortcutManager.getMaxShortcutCountPerActivity() - 1;


        ShortcutInfo disconnectShortcut = new ShortcutInfo.Builder(getContext(), "disconnectVPN")
                .setShortLabel("Disconnect")
                .setLongLabel("Disconnect VPN")
                .setIntent(new Intent(getContext(), DisconnectVPN.class).setAction(DISCONNECT_VPN))
                .setIcon(Icon.createWithResource(getContext(), R.drawable.ic_shortcut_cancel))
                .setExtras(versionExtras)
                .build();

        LinkedList<ShortcutInfo> newShortcuts = new LinkedList<>();
        LinkedList<ShortcutInfo> updateShortcuts = new LinkedList<>();

        LinkedList<String> removeShortcuts = new LinkedList<>();
        LinkedList<String> disableShortcuts = new LinkedList<>();

        boolean addDisconnect = true;


        TreeSet<VpnProfile> sortedProfilesLRU = new TreeSet<VpnProfile>(new VpnProfileLRUComparator());
        ProfileManager profileManager = ProfileManager.getInstance(getContext());
        sortedProfilesLRU.addAll(profileManager.getProfiles());

        LinkedList<VpnProfile> LRUProfiles = new LinkedList<>();
        maxvpn = Math.min(maxvpn, sortedProfilesLRU.size());

        for (int i = 0; i < maxvpn; i++) {
            LRUProfiles.add(sortedProfilesLRU.pollFirst());
        }

        for (ShortcutInfo shortcut : shortcuts) {
            if (shortcut.getId().equals("disconnectVPN")) {
                addDisconnect = false;
                if (shortcut.getExtras() == null
                        || shortcut.getExtras().getInt("version") != SHORTCUT_VERSION)
                    updateShortcuts.add(disconnectShortcut);

            } else {
                VpnProfile p = ProfileManager.get(getContext(), shortcut.getId());
                if (p == null || p.profileDeleted) {
                    if (shortcut.isEnabled()) {
                        disableShortcuts.add(shortcut.getId());
                        removeShortcuts.add(shortcut.getId());
                    }
                    if (!shortcut.isPinned())
                        removeShortcuts.add(shortcut.getId());
                } else {

                    if (LRUProfiles.contains(p))
                        LRUProfiles.remove(p);
                    else
                        removeShortcuts.add(p.getUUIDString());

                    if (!p.getName().equals(shortcut.getShortLabel())
                            || shortcut.getExtras() == null
                            || shortcut.getExtras().getInt("version") != SHORTCUT_VERSION)
                        updateShortcuts.add(createShortcut(p));


                }

            }

        }
        if (addDisconnect)
            newShortcuts.add(disconnectShortcut);
        for (VpnProfile p : LRUProfiles)
            newShortcuts.add(createShortcut(p));

        if (updateShortcuts.size() > 0)
            shortcutManager.updateShortcuts(updateShortcuts);
        if (removeShortcuts.size() > 0)
            shortcutManager.removeDynamicShortcuts(removeShortcuts);
        if (newShortcuts.size() > 0)
            shortcutManager.addDynamicShortcuts(newShortcuts);
        if (disableShortcuts.size() > 0)
            shortcutManager.disableShortcuts(disableShortcuts, "VpnProfile does not exist anymore.");
    }

    @RequiresApi(Build.VERSION_CODES.N_MR1)
    ShortcutInfo createShortcut(VpnProfile profile) {
        Intent shortcutIntent = new Intent(Intent.ACTION_MAIN);
        shortcutIntent.setClass(getActivity(), LaunchVPN.class);
        shortcutIntent.putExtra(LaunchVPN.EXTRA_KEY, profile.getUUID().toString());
        shortcutIntent.setAction(Intent.ACTION_MAIN);
        shortcutIntent.putExtra("EXTRA_HIDELOG", true);

        PersistableBundle versionExtras = new PersistableBundle();
        versionExtras.putInt("version", SHORTCUT_VERSION);

        return new ShortcutInfo.Builder(getContext(), profile.getUUIDString())
                .setShortLabel(profile.getName())
                .setLongLabel(getString(R.string.qs_connect, profile.getName()))
                .setIcon(Icon.createWithResource(getContext(), R.drawable.ic_shortcut_vpn_key))
                .setIntent(shortcutIntent)
                .setExtras(versionExtras)
                .build();
    }

    @Override
    public void onResume() {
        super.onResume();
        setListAdapter();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            updateDynamicShortcuts();
        }
        VpnStatus.addStateListener(this);
        defaultVPN = ProfileManager.getAlwaysOnVPN(requireContext());
    }

    @Override
    public void onPause() {
        super.onPause();
        VpnStatus.removeStateListener(this);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.vpn_profile_list, container, false);

        TextView newvpntext = (TextView) v.findViewById(R.id.add_new_vpn_hint);
        TextView importvpntext = (TextView) v.findViewById(R.id.import_vpn_hint);

        newvpntext.setText(Html.fromHtml(getString(R.string.add_new_vpn_hint), new MiniImageGetter(), null));
        importvpntext.setText(Html.fromHtml(getString(R.string.vpn_import_hint), new MiniImageGetter(), null));

        ImageButton fab_add = (ImageButton) v.findViewById(R.id.fab_add);
        ImageButton fab_import = (ImageButton) v.findViewById(R.id.fab_import);
        if (fab_add != null)
            fab_add.setOnClickListener(this);

        if (fab_import != null)
            fab_import.setOnClickListener(this);

        // ===== 下拉刷新: 手动重测全部节点延迟(自添加) =====
        SwipeRefreshLayout srl = new SwipeRefreshLayout(getActivity());
        srl.addView(v);
        srl.setOnChildScrollUpCallback((parent, child) -> {
            ListView lv = getListView();
            return lv != null && lv.canScrollVertically(-1);
        });
        srl.setOnRefreshListener(() -> {
            sLatencyMap.clear();
            sLastProbeTime = 0;
            sProbing = false;
            startLatencyProbe();
        });
        mSwipeRefresh = srl;
        return srl;

    }

    // ===== 节点延迟探测: 后台并发 ping 全部节点,结果写入 sLatencyMap(自添加) =====
    private void startLatencyProbe() {
        final long now = System.currentTimeMillis();
        if (sProbing) return;
        if (now - sLastProbeTime < PROBE_TTL_MS) {
            mArrayadapter.notifyDataSetChanged();
            return;
        }
        if (getActivity() == null || mArrayadapter == null) return;
        sProbing = true;
        sLastProbeTime = now;
        final List<VpnProfile> targets = new LinkedList<>();
        for (int i = 0; i < mArrayadapter.getCount(); i++) {
            VpnProfile p = mArrayadapter.getItem(i);
            if (p != null) targets.add(p);
        }
        final int total = targets.size();
        if (total == 0) {
            sProbing = false;
            return;
        }
        if (sProbePool == null || sProbePool.isShutdown()) {
            sProbePool = Executors.newFixedThreadPool(12, r -> {
                Thread t = new Thread(r, "latency-probe");
                t.setDaemon(true);
                return t;
            });
        }
        final AtomicInteger done = new AtomicInteger(0);
        for (final VpnProfile p : targets) {
            sProbePool.execute(() -> {
                final String addr = serverOf(p);
                if (addr != null && !sLatencyMap.containsKey(addr)) {
                    sLatencyMap.put(addr, pingHost(addr));
                }
                int d = done.incrementAndGet();
                if (d % 5 == 0 || d >= total) {
                    final boolean finished = (d >= total);
                    if (finished) sProbing = false;
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (mArrayadapter != null) mArrayadapter.notifyDataSetChanged();
                            if (finished) {
                                // 全部测完: 按延迟排序 + 停止下拉刷新动画
                                sortByLatency();
                                if (mSwipeRefresh != null) mSwipeRefresh.setRefreshing(false);
                            }
                        });
                    }
                }
            });
        }
    }

    private static String serverOf(VpnProfile p) {
        try {
            if (p.mConnections != null && p.mConnections.length > 0
                    && p.mConnections[0].mServerName != null
                    && !p.mConnections[0].mServerName.isEmpty()) {
                return p.mConnections[0].mServerName;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String pingHost(String host) {
        Process proc = null;
        try {
            proc = Runtime.getRuntime().exec(new String[]{"/system/bin/ping", "-c", "2", "-W", "2", host});
            if (!proc.waitFor(8, TimeUnit.SECONDS)) {
                proc.destroy();
                return "无响应";
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line;
            java.util.regex.Pattern pat = java.util.regex.Pattern.compile("min/avg/max[^=]*= [\\d.]+/([\\d.]+)");
            while ((line = br.readLine()) != null) {
                java.util.regex.Matcher m = pat.matcher(line);
                if (m.find()) {
                    br.close();
                    return Math.round(Double.parseDouble(m.group(1))) + "ms";
                }
            }
            br.close();
            return "无响应";
        } catch (Throwable t) {
            return "无响应";
        } finally {
            if (proc != null) proc.destroy();
        }
    }

    // ===== 按延迟排序: 延迟小的在前,无响应最后,未测次后(自添加) =====
    private void sortByLatency() {
        if (mArrayadapter == null || mArrayadapter.getCount() == 0) return;
        try {
            List<VpnProfile> list = new ArrayList<>();
            for (int i = 0; i < mArrayadapter.getCount(); i++) {
                VpnProfile p = mArrayadapter.getItem(i);
                if (p != null) list.add(p);
            }
            Collections.sort(list, (a, b) -> {
                long la = latencyRank(a), lb = latencyRank(b);
                if (la != lb) return Long.compare(la, lb);
                return a.getName().compareToIgnoreCase(b.getName());
            });
            mArrayadapter.clear();
            mArrayadapter.addAll(list);
            mArrayadapter.notifyDataSetChanged();
        } catch (Throwable ignored) {
        }
    }

    private static long latencyRank(VpnProfile p) {
        String addr = serverOf(p);
        String lat = addr != null ? sLatencyMap.get(addr) : null;
        if (lat == null) return 999998;            // 未测到
        if ("无响应".equals(lat)) return 999999;    // 不通
        try {
            return Long.parseLong(lat.replace("ms", "").trim());
        } catch (Exception e) {
            return 999998;
        }
    }

    private void setListAdapter() {
        if (mArrayadapter == null) {
            mArrayadapter = new VPNArrayAdapter(getActivity(), R.layout.vpn_list_item, R.id.vpn_item_title);

        }
        populateVpnList();
    }

    private void populateVpnList() {
        boolean sortByLRU = Preferences.getDefaultSharedPreferences(requireActivity()).getBoolean(PREF_SORT_BY_LRU, false);
        Collection<VpnProfile> allvpn = getPM().getProfiles();
        TreeSet<VpnProfile> sortedset;
        if (sortByLRU)
            sortedset = new TreeSet<>(new VpnProfileLRUComparator());
        else
            sortedset = new TreeSet<>(new VpnProfileNameComparator());

        sortedset.addAll(allvpn);
        mArrayadapter.clear();
        mArrayadapter.addAll(sortedset);

        setListAdapter(mArrayadapter);
        mArrayadapter.notifyDataSetChanged();
        startLatencyProbe();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, @NonNull MenuInflater inflater) {
        menu.add(0, MENU_ADD_PROFILE, 0, R.string.menu_add_profile)
                .setIcon(R.drawable.ic_menu_add)
                .setAlphabeticShortcut('a')
                .setTitleCondensed(getActivity().getString(R.string.add))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

        menu.add(0, MENU_IMPORT_PROFILE, 0, R.string.menu_import)
                .setIcon(R.drawable.ic_menu_import)
                .setAlphabeticShortcut('i')
                .setTitleCondensed(getActivity().getString(R.string.menu_import_short))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

        menu.add(0, MENU_CHANGE_SORTING, 0, R.string.change_sorting)
                .setIcon(R.drawable.ic_sort)
                .setAlphabeticShortcut('s')
                .setTitleCondensed(getString(R.string.sort))
                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM);

        menu.add(0, MENU_IMPORT_AS, 0, R.string.import_from_as)
                .setIcon(R.drawable.ic_menu_import)
                .setAlphabeticShortcut('p')
                .setTitleCondensed("Import AS")
                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM);

    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == MENU_ADD_PROFILE) {
            onAddOrDuplicateProfile(null);
            return true;
        } else if (itemId == MENU_IMPORT_PROFILE) {
            return startImportConfigFilePicker();
        } else if (itemId == MENU_CHANGE_SORTING) {
            return changeSorting();
        } else if (itemId == MENU_IMPORT_AS) {
            return startASProfileImport();
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    private boolean startASProfileImport() {
        ImportRemoteConfig asImportFrag = ImportRemoteConfig.newInstance(null);
        asImportFrag.show(getParentFragmentManager(), "dialog");
        return true;
    }

    private boolean changeSorting() {
        SharedPreferences prefs = Preferences.getDefaultSharedPreferences(requireActivity());
        boolean oldValue = prefs.getBoolean(PREF_SORT_BY_LRU, false);
        SharedPreferences.Editor prefsedit = prefs.edit();
        if (oldValue) {
            Toast.makeText(getActivity(), R.string.sorted_az, Toast.LENGTH_SHORT).show();
            prefsedit.putBoolean(PREF_SORT_BY_LRU, false);
        } else {
            prefsedit.putBoolean(PREF_SORT_BY_LRU, true);
            Toast.makeText(getActivity(), R.string.sorted_lru, Toast.LENGTH_SHORT).show();
        }
        prefsedit.apply();
        populateVpnList();
        return true;
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.fab_import:
                startImportConfigFilePicker();
                break;
            case R.id.fab_add:
                onAddOrDuplicateProfile(null);
                break;
        }
    }

    private boolean startImportConfigFilePicker() {
        boolean startOldFileDialog = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && !Utils.alwaysUseOldFileChooser(getActivity()))
            startOldFileDialog = !startFilePicker();

        if (startOldFileDialog)
            startImportConfig();

        return true;
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private boolean startFilePicker() {

        Intent i = Utils.getFilePickerIntent(getActivity(), Utils.FileType.OVPN_CONFIG);
        if (i != null) {
            startActivityForResult(i, FILE_PICKER_RESULT_KITKAT);
            return true;
        } else
            return false;
    }

    private void startImportConfig() {
        Intent intent = new Intent(getActivity(), FileSelect.class);
        intent.putExtra(FileSelect.NO_INLINE_SELECTION, true);
        intent.putExtra(FileSelect.WINDOW_TITLE, R.string.import_configuration_file);
        startActivityForResult(intent, SELECT_PROFILE);
    }

    private void onAddOrDuplicateProfile(final VpnProfile mCopyProfile) {
        Context context = getActivity();
        if (context != null) {
            final EditText entry = new EditText(context);
            entry.setSingleLine();

            AlertDialog.Builder dialog = new AlertDialog.Builder(context);
            if (mCopyProfile == null)
                dialog.setTitle(R.string.menu_add_profile);
            else {
                dialog.setTitle(context.getString(R.string.duplicate_profile_title, mCopyProfile.mName));
                entry.setText(getString(R.string.copy_of_profile, mCopyProfile.mName));
            }

            dialog.setMessage(R.string.add_profile_name_prompt);
            dialog.setView(entry);

            dialog.setNeutralButton(R.string.menu_import_short,
                    (dialog1, which) -> startImportConfigFilePicker());
            dialog.setPositiveButton(android.R.string.ok,
                    (dialog12, which) -> {
                        String name = entry.getText().toString();
                        if (getPM().getProfileByName(name) == null) {
                            VpnProfile profile;
                            if (mCopyProfile != null) {
                                profile = mCopyProfile.copy(name);
                                // Remove restrictions on copy profile
                                profile.mProfileCreator = null;
                                profile.mUserEditable = true;
                            } else
                                profile = new VpnProfile(name);

                            addProfile(profile);
                            editVPN(profile);
                        } else {
                            Toast.makeText(getActivity(), R.string.duplicate_profile_name, Toast.LENGTH_LONG).show();
                        }
                    });
            dialog.setNegativeButton(android.R.string.cancel, null);
            dialog.create().show();
        }

    }

    private void addProfile(VpnProfile profile) {
        getPM().addProfile(profile);
        getPM().saveProfileList(getActivity());
        getPM().saveProfile(getActivity(), profile);
        mArrayadapter.add(profile);
    }

    private ProfileManager getPM() {
        return ProfileManager.getInstance(getActivity());
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_VPN_DELETED) {
            if (mArrayadapter != null && mEditProfile != null)
                mArrayadapter.remove(mEditProfile);
        } else if (resultCode == RESULT_VPN_DUPLICATE && data != null) {
            String profileUUID = data.getStringExtra(VpnProfile.EXTRA_PROFILEUUID);
            VpnProfile profile = ProfileManager.get(getActivity(), profileUUID);
            if (profile != null)
                onAddOrDuplicateProfile(profile);
        }


        if (resultCode != Activity.RESULT_OK)
            return;


        if (requestCode == START_VPN_CONFIG) {
            String configuredVPN = data.getStringExtra(VpnProfile.EXTRA_PROFILEUUID);

            VpnProfile profile = ProfileManager.get(getActivity(), configuredVPN);
            getPM().saveProfile(getActivity(), profile);
            // Name could be modified, reset List adapter
            setListAdapter();

        } else if (requestCode == SELECT_PROFILE) {
            String fileData = data.getStringExtra(FileSelect.RESULT_DATA);
            Uri uri = new Uri.Builder().path(fileData).scheme("file").build();

            startConfigImport(uri);
        } else if (requestCode == IMPORT_PROFILE) {
            String profileUUID = data.getStringExtra(VpnProfile.EXTRA_PROFILEUUID);
            mArrayadapter.add(ProfileManager.get(getActivity(), profileUUID));
        } else if (requestCode == FILE_PICKER_RESULT_KITKAT) {
            if (data != null) {
                Uri uri = data.getData();
                startConfigImport(uri);
            }
        }

    }

    private void startConfigImport(Uri uri) {
        Intent startImport = new Intent(getActivity(), ConfigConverter.class);
        startImport.setAction(ConfigConverter.IMPORT_PROFILE);
        startImport.setData(uri);
        startActivityForResult(startImport, IMPORT_PROFILE);
    }

    private void editVPN(VpnProfile profile) {
        mEditProfile = profile;
        Intent vprefintent = new Intent(getActivity(), VPNPreferences.class)
                .putExtra(getActivity().getPackageName() + ".profileUUID", profile.getUUID().toString());

        startActivityForResult(vprefintent, START_VPN_CONFIG);
    }

    private void startVPN(VpnProfile profile) {

        getPM().saveProfile(getActivity(), profile);

        Intent intent = new Intent(getActivity(), LaunchVPN.class);
        intent.putExtra(LaunchVPN.EXTRA_KEY, profile.getUUID().toString());
        intent.setAction(Intent.ACTION_MAIN);
        startActivity(intent);
    }

    static class VpnProfileNameComparator implements Comparator<VpnProfile> {

        @Override
        public int compare(VpnProfile lhs, VpnProfile rhs) {
            if (lhs == rhs)
                // Catches also both null
                return 0;

            if (lhs == null)
                return -1;
            if (rhs == null)
                return 1;

            if (lhs.mName == null)
                return -1;
            if (rhs.mName == null)
                return 1;

            return lhs.mName.compareTo(rhs.mName);
        }

    }

    static class VpnProfileLRUComparator implements Comparator<VpnProfile> {

        VpnProfileNameComparator nameComparator = new VpnProfileNameComparator();

        @Override
        public int compare(VpnProfile lhs, VpnProfile rhs) {
            if (lhs == rhs)
                // Catches also both null
                return 0;

            if (lhs == null)
                return -1;
            if (rhs == null)
                return 1;

            // Copied from Long.compare
            if (lhs.mLastUsed > rhs.mLastUsed)
                return -1;
            if (lhs.mLastUsed < rhs.mLastUsed)
                return 1;
            else
                return nameComparator.compare(lhs, rhs);
        }
    }

    private class VPNArrayAdapter extends ArrayAdapter<VpnProfile> {

        public VPNArrayAdapter(Context context, int resource,
                               int textViewResourceId) {
            super(context, resource, textViewResourceId);
        }

        @NonNull
        @Override
        public View getView(final int position, View convertView, @NonNull ViewGroup parent) {
            View v = super.getView(position, convertView, parent);

            final VpnProfile profile = (VpnProfile) getListAdapter().getItem(position);

            View titleview = v.findViewById(R.id.vpn_list_item_left);
            titleview.setOnClickListener(v1 -> startOrStopVPN(profile));

            View settingsview = v.findViewById(R.id.quickedit_settings);
            settingsview.setOnClickListener(view -> editVPN(profile));

            TextView subtitle = v.findViewById(R.id.vpn_item_subtitle);
            SpannableStringBuilder warningText = Utils.getWarningText(requireContext(), profile);

            if (profile == defaultVPN) {
                if (warningText.length() > 0)
                    warningText.append(" ");
                warningText.append(new SpannableString("Default VPN"));
            }

            if (profile.getUUIDString().equals(VpnStatus.getLastConnectedVPNProfile())) {
                subtitle.setText(mLastStatusMessage);
                subtitle.setVisibility(View.VISIBLE);
            } else {
                subtitle.setText(warningText);
                if (warningText.length() > 0) {
                    subtitle.setVisibility(View.VISIBLE);
                } else {
                    String addr = serverOf(profile);
                    String lat = addr != null ? sLatencyMap.get(addr) : null;
                    if (lat != null) {
                        subtitle.setText("延迟 " + lat);
                        subtitle.setVisibility(View.VISIBLE);
                    } else {
                        subtitle.setVisibility(View.GONE);
                    }
                }
            }


            return v;
        }
    }

    class MiniImageGetter implements ImageGetter {


        @Override
        public Drawable getDrawable(String source) {
            Drawable d = null;
            if ("ic_menu_add".equals(source))
                d = requireActivity().getResources().getDrawable(R.drawable.ic_menu_add_grey);
            else if ("ic_menu_archive".equals(source))
                d = requireActivity().getResources().getDrawable(R.drawable.ic_menu_import_grey);


            if (d != null) {
                d.setBounds(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
                return d;
            } else {
                return null;
            }
        }
    }
}
