package com.blockstream.green.ui.devices

import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.blockstream.green.R
import com.blockstream.green.databinding.JadeGuideFragmentBinding
import com.blockstream.green.extensions.setOnClickListenerIndexed
import com.blockstream.green.ui.AppFragment
import com.blockstream.green.utils.fadeIn
import com.blockstream.green.utils.fadeOut
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.time.DurationUnit
import kotlin.time.toDuration


@AndroidEntryPoint
class JadeGuideFragment : AppFragment<JadeGuideFragmentBinding>(
    layout = R.layout.jade_guide_fragment,
    menuRes = 0
) {
    private var changeStepJob: Job? = null
    override val screenName = "JadeSetupGuid"

    var step = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonExit.setOnClickListener {
            popBackStack()
        }

        binding.step1.text = "${getString(R.string.id_step)} 1".uppercase()
        binding.step2.text = "${getString(R.string.id_step)} 2".uppercase()
        binding.step3.text = "${getString(R.string.id_step)} 3".uppercase()

        listOf(binding.steps1, binding.steps2, binding.steps3).setOnClickListenerIndexed { i, v ->
            step = i
            updateUI()
            changeStep()
        }

        updateUI()
        changeStep()
    }

    private fun updateUI() {
        binding.step = step

        listOf(
            binding.steps1,
            binding.steps2,
            binding.steps3
        ).forEachIndexed { index, materialCardView ->
            materialCardView.setStrokeColor(
                ContextCompat.getColorStateList(
                    requireContext(),
                    R.color.brand_green.takeIf { step == index } ?: android.R.color.transparent))
        }

        when(step){
            0 -> {
                binding.imageView1.fadeIn()
                binding.imageView2.fadeOut(duration = 500)
                binding.imageView3.fadeOut(duration = 500)
            }
            1 -> {
                binding.imageView1.fadeOut(duration = 500)
                binding.imageView2.fadeIn()
                binding.imageView3.fadeOut(duration = 500)
            }
            else -> {
                binding.imageView1.fadeOut(duration = 500)
                binding.imageView2.fadeOut(duration = 500)
                binding.imageView3.fadeIn()
            }
        }
    }

    private fun changeStep() {
        changeStepJob?.cancel()
        changeStepJob = lifecycleScope.launchWhenResumed {
            delay(5.toDuration(DurationUnit.SECONDS))
            step = (step + 1).takeIf { it < 3 } ?: 0
            updateUI()
            changeStep()
        }
    }
}