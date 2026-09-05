package com.fongmi.android.tv.ui.custom;

import static androidx.fragment.app.FragmentTransaction.TRANSIT_FRAGMENT_OPEN;

import android.view.ViewGroup;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.base.BaseFragment;

import java.util.function.IntFunction;

public class FragmentStateManager {

    private final ViewGroup container;
    private final FragmentManager fm;
    private final IntFunction<Fragment> factory;

    public FragmentStateManager(ViewGroup container, FragmentManager fm, IntFunction<Fragment> factory) {
        this.container = container;
        this.factory = factory;
        this.fm = fm;
    }

    public boolean change(int position) {
        String tag = getTag(position);
        Fragment fragment = fm.findFragmentByTag(tag);
        fragment = (fragment == null) ? factory.apply(position) : fragment;
        // 板块切换动画：轻滑淡入（tab_in） + 淡出（tab_out），原生平滑切换
        FragmentTransaction ft = fm.beginTransaction()
                .setCustomAnimations(R.anim.tab_in, R.anim.tab_out)
                .setTransition(TRANSIT_FRAGMENT_OPEN);
        if (fm.findFragmentByTag(tag) == null) ft.add(container.getId(), fragment, tag);
        Fragment current = fm.getPrimaryNavigationFragment();
        if (current != null && current != fragment) ft.hide(current);
        ft.show(fragment).setPrimaryNavigationFragment(fragment).setReorderingAllowed(true).commitNowAllowingStateLoss();
        return true;
    }

    private String getTag(int position) {
        return "android:switcher:" + position;
    }

    public Fragment getFragment(int position) {
        return fm.findFragmentByTag(getTag(position));
    }

    public boolean isVisible(int position) {
        Fragment fragment = getFragment(position);
        return fragment != null && fragment.isVisible();
    }

    public boolean canBack(int position) {
        Fragment fragment = getFragment(position);
        return fragment instanceof BaseFragment && ((BaseFragment) fragment).canBack();
    }
}
