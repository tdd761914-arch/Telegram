/*
 * This is the source code of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package org.telegram.ui;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.WhitelistBypassManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

public class WhitelistBypassActivity extends BaseFragment implements WhitelistBypassManager.Listener {

    private EditTextBoldCursor linkField;
    private EditTextBoldCursor nameField;
    private RadioCell videoCell;
    private RadioCell dcCell;
    private TextCheckCell enableCell;
    private TextInfoPrivacyCell statusCell;
    private WebView captchaWebView;
    private String tunnelMode;
    private String loadedCaptchaUrl = "";

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.WhitelistBypassTitle));
        actionBar.setAllowOverlayTitle(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = root;

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        AndroidUtilities.setScrollViewEdgeEffectColor(scrollView, Theme.getColor(Theme.key_actionBarDefault));
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        HeaderCell creatorHeader = new HeaderCell(context);
        creatorHeader.setText(LocaleController.getString(R.string.WhitelistBypassCreatorHeader));
        creatorHeader.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        content.addView(creatorHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout fieldsContainer = new LinearLayout(context);
        fieldsContainer.setOrientation(LinearLayout.VERTICAL);
        fieldsContainer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        content.addView(fieldsContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        linkField = createInputField(context, LocaleController.getString(R.string.WhitelistBypassCreatorLink), true);
        linkField.setText(WhitelistBypassManager.getCreatorLink());
        linkField.setSelection(linkField.length());
        fieldsContainer.addView(wrapInput(context, linkField, true), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));

        nameField = createInputField(context, LocaleController.getString(R.string.WhitelistBypassDisplayName), false);
        nameField.setText(WhitelistBypassManager.getDisplayName());
        nameField.setSelection(nameField.length());
        nameField.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        fieldsContainer.addView(wrapInput(context, nameField, false), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));

        TextSettingsCell pasteCell = new TextSettingsCell(context);
        pasteCell.setBackground(Theme.getSelectorDrawable(true));
        pasteCell.setText(LocaleController.getString(R.string.PasteFromClipboard), false);
        pasteCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        pasteCell.setOnClickListener(v -> pasteCreatorLink(context));
        content.addView(pasteCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        content.addView(createShadow(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        HeaderCell modeHeader = new HeaderCell(context);
        modeHeader.setText(LocaleController.getString(R.string.WhitelistBypassModeHeader));
        modeHeader.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        content.addView(modeHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        tunnelMode = WhitelistBypassManager.getTunnelMode();
        videoCell = new RadioCell(context);
        videoCell.setText(LocaleController.getString(R.string.WhitelistBypassModeVideo), WhitelistBypassManager.MODE_VIDEO.equals(tunnelMode), true);
        videoCell.setBackground(Theme.getSelectorDrawable(true));
        videoCell.setOnClickListener(v -> setTunnelMode(WhitelistBypassManager.MODE_VIDEO, true));
        content.addView(videoCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        dcCell = new RadioCell(context);
        dcCell.setText(LocaleController.getString(R.string.WhitelistBypassModeDc), WhitelistBypassManager.MODE_DC.equals(tunnelMode), false);
        dcCell.setBackground(Theme.getSelectorDrawable(true));
        dcCell.setOnClickListener(v -> {
            if (dcCell.isEnabled()) {
                setTunnelMode(WhitelistBypassManager.MODE_DC, true);
            }
        });
        content.addView(dcCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        TextInfoPrivacyCell modeInfo = new TextInfoPrivacyCell(context);
        modeInfo.setText(LocaleController.getString(R.string.WhitelistBypassModeInfo));
        modeInfo.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
        content.addView(modeInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        enableCell = new TextCheckCell(context);
        enableCell.setBackground(Theme.getSelectorDrawable(true));
        enableCell.setTextAndCheck(LocaleController.getString(R.string.WhitelistBypassEnable), WhitelistBypassManager.isEnabled(), false);
        enableCell.setOnClickListener(v -> toggleConnection(context));
        content.addView(enableCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        statusCell = new TextInfoPrivacyCell(context);
        statusCell.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
        content.addView(statusCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        captchaWebView = new WebView(context);
        captchaWebView.setVisibility(View.GONE);
        captchaWebView.setBackgroundColor(0xffffffff);
        captchaWebView.setWebViewClient(new WebViewClient());
        content.addView(captchaWebView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 420));

        TextInfoPrivacyCell explanationCell = new TextInfoPrivacyCell(context);
        explanationCell.setText(LocaleController.getString(R.string.WhitelistBypassInfo));
        explanationCell.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
        content.addView(explanationCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        linkField.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                updateModeAvailability();
            }
        });
        linkField.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                nameField.requestFocus();
                return true;
            }
            return false;
        });
        nameField.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                AndroidUtilities.hideKeyboard(nameField);
                return true;
            }
            return false;
        });

        updateModeAvailability();
        updateState();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        WhitelistBypassManager.addListener(this);
        if (WhitelistBypassManager.isEnabled() && !WhitelistBypassManager.isServiceRunning()) {
            WhitelistBypassManager.restartIfNeeded(getParentActivity());
        }
        updateState();
    }

    @Override
    public void onPause() {
        if (!WhitelistBypassManager.isEnabled()) {
            saveForm();
        }
        WhitelistBypassManager.removeListener(this);
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        WhitelistBypassManager.removeListener(this);
        if (captchaWebView != null) {
            captchaWebView.stopLoading();
            captchaWebView.loadUrl("about:blank");
            captchaWebView.destroy();
            captchaWebView = null;
        }
        super.onFragmentDestroy();
    }

    @Override
    public void onWhitelistBypassStateChanged() {
        if (fragmentView != null) {
            updateState();
        }
    }

    private EditTextBoldCursor createInputField(Context context, String hint, boolean uri) {
        EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setBackground(null);
        field.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setCursorSize(AndroidUtilities.dp(20));
        field.setCursorWidth(1.5f);
        field.setSingleLine(true);
        field.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        field.setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        field.setTransformHintToHeader(true);
        field.setLineColors(
                Theme.getColor(Theme.key_windowBackgroundWhiteInputField),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
                Theme.getColor(Theme.key_text_RedRegular)
        );
        field.setHintText(hint);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | (uri ? InputType.TYPE_TEXT_VARIATION_URI : InputType.TYPE_TEXT_VARIATION_PERSON_NAME));
        field.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setPadding(0, 0, 0, 0);
        if (!uri) {
            field.setTypeface(Typeface.DEFAULT);
        }
        return field;
    }

    private FrameLayout wrapInput(Context context, EditTextBoldCursor field, boolean topPadding) {
        FrameLayout container = new FrameLayout(context);
        container.addView(field, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT,
                Gravity.LEFT | Gravity.TOP,
                17,
                topPadding ? 12 : 0,
                17,
                0
        ));
        return container;
    }

    private ShadowSectionCell createShadow(Context context) {
        ShadowSectionCell shadow = new ShadowSectionCell(context);
        shadow.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
        return shadow;
    }

    private void pasteCreatorLink(Context context) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return;
        }
        CharSequence value = clip.getItemAt(0).coerceToText(context);
        if (!TextUtils.isEmpty(value)) {
            linkField.setText(value.toString().trim());
            linkField.setSelection(linkField.length());
        }
    }

    private void toggleConnection(Context context) {
        if (WhitelistBypassManager.isEnabled()) {
            WhitelistBypassManager.stop(context);
            return;
        }

        saveForm();
        String error = WhitelistBypassManager.validateCreatorLink(linkField.getText().toString());
        if (error != null) {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle(LocaleController.getString(R.string.WhitelistBypassTitle));
            builder.setMessage(error);
            builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
            showDialog(builder.create());
            return;
        }
        AndroidUtilities.hideKeyboard(fragmentView.findFocus());
        error = WhitelistBypassManager.start(context);
        if (error != null) {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle(LocaleController.getString(R.string.WhitelistBypassTitle));
            builder.setMessage(error);
            builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
            showDialog(builder.create());
        }
        updateState();
    }

    private void saveForm() {
        if (linkField == null || nameField == null) {
            return;
        }
        WhitelistBypassManager.saveSettings(
                linkField.getText().toString(),
                nameField.getText().toString(),
                tunnelMode
        );
    }

    private void setTunnelMode(String mode, boolean animated) {
        tunnelMode = mode;
        videoCell.setChecked(WhitelistBypassManager.MODE_VIDEO.equals(mode), animated);
        dcCell.setChecked(WhitelistBypassManager.MODE_DC.equals(mode), animated);
    }

    private void updateModeAvailability() {
        if (dcCell == null || linkField == null) {
            return;
        }
        boolean enabled = !WhitelistBypassManager.isVideoOnlyLink(linkField.getText().toString());
        dcCell.setEnabled(enabled, null);
        if (!enabled && WhitelistBypassManager.MODE_DC.equals(tunnelMode)) {
            setTunnelMode(WhitelistBypassManager.MODE_VIDEO, true);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void updateState() {
        if (enableCell == null) {
            return;
        }
        boolean enabled = WhitelistBypassManager.isEnabled();
        enableCell.setChecked(enabled);
        linkField.setEnabled(!enabled);
        nameField.setEnabled(!enabled);
        videoCell.setEnabled(!enabled, null);
        dcCell.setEnabled(!enabled && !WhitelistBypassManager.isVideoOnlyLink(linkField.getText().toString()), null);

        float inputAlpha = enabled ? 0.55f : 1f;
        linkField.setAlpha(inputAlpha);
        nameField.setAlpha(inputAlpha);

        String status;
        switch (WhitelistBypassManager.getState()) {
            case STARTING:
                status = LocaleController.getString(R.string.WhitelistBypassStatusStarting);
                break;
            case CONNECTING:
                status = LocaleController.getString(R.string.WhitelistBypassStatusConnecting);
                break;
            case CAPTCHA:
                status = LocaleController.getString(R.string.WhitelistBypassStatusCaptcha);
                break;
            case CONNECTED:
                status = LocaleController.getString(R.string.WhitelistBypassStatusConnected);
                if (!TextUtils.isEmpty(WhitelistBypassManager.getStatusDetail())) {
                    status += "\n" + WhitelistBypassManager.getStatusDetail();
                }
                break;
            case RECONNECTING:
                status = LocaleController.getString(R.string.WhitelistBypassStatusReconnecting);
                break;
            case ERROR:
                status = LocaleController.getString(R.string.WhitelistBypassStatusError);
                if (!TextUtils.isEmpty(WhitelistBypassManager.getStatusDetail())) {
                    status += "\n" + WhitelistBypassManager.getStatusDetail();
                }
                break;
            case OFF:
            default:
                status = LocaleController.getString(R.string.WhitelistBypassStatusOff);
                break;
        }
        statusCell.setText(status);

        String captchaUrl = WhitelistBypassManager.getCaptchaUrl();
        if (!TextUtils.isEmpty(captchaUrl)) {
            captchaWebView.getSettings().setJavaScriptEnabled(true);
            captchaWebView.getSettings().setDomStorageEnabled(true);
            captchaWebView.setVisibility(View.VISIBLE);
            if (!TextUtils.equals(loadedCaptchaUrl, captchaUrl)) {
                loadedCaptchaUrl = captchaUrl;
                captchaWebView.loadUrl(captchaUrl);
            }
        } else if (captchaWebView.getVisibility() != View.GONE) {
            loadedCaptchaUrl = "";
            captchaWebView.stopLoading();
            captchaWebView.loadUrl("about:blank");
            captchaWebView.setVisibility(View.GONE);
        }
    }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    }
}
