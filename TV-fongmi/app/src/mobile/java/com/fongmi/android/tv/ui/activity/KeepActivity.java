package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.compose.ui.platform.ComposeView;
import androidx.compose.ui.platform.ViewCompositionStrategy;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.ActivityKeepBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.compose.VodPagesKt;

public class KeepActivity extends BaseActivity {

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, KeepActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return ActivityKeepBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        ComposeView compose = new ComposeView(this);
        VodPagesKt.attachVodKeepPage(compose, this, () -> { onBackInvoked(); });
        setContentView(compose);
    }
}