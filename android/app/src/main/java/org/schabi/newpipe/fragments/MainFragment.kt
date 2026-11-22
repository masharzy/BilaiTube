package org.schabi.newpipe.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import org.schabi.newpipe.BaseFragment
import org.schabi.newpipe.R
import org.schabi.newpipe.bilai.BilaiBottomBar
import org.schabi.newpipe.bilai.BilaiTheme
import org.schabi.newpipe.bilai.HomeFeedFragment
import org.schabi.newpipe.bilai.LibraryFragment
import org.schabi.newpipe.error.ErrorUtil
import org.schabi.newpipe.local.feed.FeedFragment
import org.schabi.newpipe.fragments.list.search.SearchFragment
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.ServiceHelper
import org.schabi.newpipe.util.ThemeHelper

/**
 * The BilaiTube main page: a 56dp bottom bar switching between Home, Subscriptions and Library.
 * The destinations are kept alive (shown / hidden) so scroll positions survive tab switches.
 */
class MainFragment : BaseFragment(), BackPressable {
    private var selectedTab by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        selectedTab = savedInstanceState?.getInt(STATE_TAB, 0) ?: 0
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_main, container, false)

    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)
        val bar = rootView.findViewById<ComposeView>(R.id.main_bottom_nav)
        bar.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        bar.setContent {
            BilaiTheme(amoled = ThemeHelper.isBlackThemeSelected(requireContext())) {
                BilaiBottomBar(selected = selectedTab, onSelect = ::showTab)
            }
        }
        showTab(selectedTab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, selectedTab)
    }

    private fun showTab(index: Int) {
        selectedTab = index
        val fm = childFragmentManager
        val transaction = fm.beginTransaction()
        for (i in TAGS.indices) {
            val existing = fm.findFragmentByTag(TAGS[i])
            if (i == index) {
                if (existing == null) {
                    transaction.add(R.id.main_content, createTab(i), TAGS[i])
                } else {
                    transaction.show(existing)
                }
            } else if (existing != null) {
                transaction.hide(existing)
            }
        }
        transaction.commitNowAllowingStateLoss()
    }

    private fun createTab(index: Int): Fragment = when (index) {
        0 -> HomeFeedFragment()
        1 -> FeedFragment.newInstance().also { it.useAsFrontPage(true) }
        else -> LibraryFragment()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_main_fragment, menu)
        activity?.supportActionBar?.setDisplayHomeAsUpEnabled(false)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_search -> {
                try {
                    val serviceId = ServiceHelper.getSelectedServiceId(requireActivity())
                    NavigationHelper.openSearchFragment(
                        getFM(),
                        SearchFragment.getPersistedSearchServiceId(requireActivity(), serviceId),
                        "")
                } catch (e: Exception) {
                    ErrorUtil.showUiErrorSnackbar(this, "Opening search fragment", e)
                }
                return true
            }
            R.id.action_account -> {
                NavigationHelper.openSettings(requireContext())
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        setTitle(getString(R.string.app_name))
    }

    override fun onBackPressed(): Boolean {
        val current = childFragmentManager.findFragmentByTag(TAGS[selectedTab])
        if (current is BackPressable && current.isVisible && current.onBackPressed()) {
            return true
        }
        if (selectedTab != 0) {
            showTab(0)
            return true
        }
        return false
    }

    companion object {
        private const val STATE_TAB = "bilai_main_tab"
        private val TAGS = arrayOf("tab_home", "tab_subscriptions", "tab_library")
    }
}
