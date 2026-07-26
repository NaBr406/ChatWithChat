package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import cn.nabr.chatwithchat.data.sticker.StickerRepository
import java.io.InputStream
import javax.inject.Inject

@HiltViewModel
class StickerAssetViewModel @Inject constructor(
    private val stickerRepository: StickerRepository
) : ViewModel(), StickerAssetResolver {
    override suspend fun openStickerAsset(assetKey: String): InputStream? = stickerRepository.openAsset(assetKey)
}
