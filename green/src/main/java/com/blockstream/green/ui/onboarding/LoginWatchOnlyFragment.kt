package com.blockstream.green.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import com.blockstream.green.NavGraphDirections
import com.blockstream.green.R
import com.blockstream.green.databinding.LoginWatchOnlyFragmentBinding
import com.blockstream.green.utils.errorDialog
import com.blockstream.green.gdk.getGDKErrorCode
import com.blockstream.green.ui.wallet.LoginFragmentDirections
import com.blockstream.green.utils.hideKeyboard
import com.blockstream.libgreenaddress.KotlinGDK
import com.greenaddress.Bridge
import com.greenaddress.greenbits.ui.TabbedMainActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class LoginWatchOnlyFragment :
    AbstractOnboardingFragment<LoginWatchOnlyFragmentBinding>(
        R.layout.login_watch_only_fragment,
        menuRes = 0
    ) {

    val viewModel: LoginWatchOnlyViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.vm = viewModel

        binding.buttonConnectionSettings.setOnClickListener {
            navigate(NavGraphDirections.actionGlobalConnectionSettingsDialogFragment())
        }

        viewModel.onError.observe(viewLifecycleOwner) {
            it?.getContentIfNotHandledOrReturnNull()?.let {
                errorDialog(getString(if (it.getGDKErrorCode() == KotlinGDK.GA_ERROR) R.string.id_user_not_found_or_invalid else R.string.id_connection_failed))
            }
        }

        viewModel.newWallet.observe(viewLifecycleOwner) {
            if (it != null) {
                hideKeyboard()

                if(Bridge.useGreenModule) {
                    navigate(LoginFragmentDirections.actionGlobalOverviewFragment(it))
                }else{
                    val intent = Intent(requireContext(), TabbedMainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                }
            }
        }
    }
}