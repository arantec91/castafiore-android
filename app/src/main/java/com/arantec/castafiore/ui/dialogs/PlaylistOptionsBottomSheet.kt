package com.arantec.castafiore.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.arantec.castafiore.databinding.BottomSheetPlaylistOptionsBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class PlaylistOptionsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetPlaylistOptionsBinding? = null
    private val binding get() = _binding!!

    private var onEditNameClickListener: (() -> Unit)? = null
    private var onDeleteListClickListener: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetPlaylistOptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.setOnShowListener {
            val bottomSheetDialog = it as com.google.android.material.bottomsheet.BottomSheetDialog
            val bottomSheet = bottomSheetDialog.findViewById<android.widget.FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            bottomSheet?.background = null
            bottomSheetDialog.window?.setDimAmount(0.5f)
        }

        binding.optionEditName.setOnClickListener {
            onEditNameClickListener?.invoke()
            dismiss()
        }
        binding.optionDeleteList.setOnClickListener {
            onDeleteListClickListener?.invoke()
            dismiss()
        }
    }

    fun setOnEditNameClickListener(listener: () -> Unit): PlaylistOptionsBottomSheet {
        onEditNameClickListener = listener
        return this
    }

    fun setOnDeleteListClickListener(listener: () -> Unit): PlaylistOptionsBottomSheet {
        onDeleteListClickListener = listener
        return this
    }

    @Deprecated("Download option removed in streaming-only mode; this is a no-op.")
    fun setOnDownloadClickListener(@Suppress("UNUSED_PARAMETER") listener: () -> Unit): PlaylistOptionsBottomSheet {
        return this
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
