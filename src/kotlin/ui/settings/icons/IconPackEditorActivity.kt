package desu.mintgram.ui.settings.icons

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import desu.mintgram.helpers.icons.IconPackStorage
import desu.mintgram.ui.settings.SettingsPageActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

/** Per-icon replacement picker for one custom pack — grid of every icon [IconPackStorage] considers
 * replaceable, filterable by replaced/unreplaced, tap opens the gallery to pick a new image. */
class IconPackEditorActivity(private val packId: String) : SettingsPageActivity() {
    private var filterMode = FILTER_ALL
    private var pendingIconName: String? = null
    private var gridAdapter: IconGridAdapter? = null
    private var gridView: RecyclerListView? = null

    override fun getTitle(): CharSequence =
        IconPackStorage.getPack(packId)?.name ?: LocaleController.getString(R.string.InuIconPacks)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuIconPacksEditorInfo)))

        items.add(UItem.asRadio(FILTER_ALL, LocaleController.getString(R.string.InuIconPacksFilterAll)).setChecked(filterMode == FILTER_ALL))
        items.add(UItem.asRadio(FILTER_REPLACED, LocaleController.getString(R.string.InuIconPacksFilterReplaced)).setChecked(filterMode == FILTER_REPLACED))
        items.add(UItem.asRadio(FILTER_UNREPLACED, LocaleController.getString(R.string.InuIconPacksFilterUnreplaced)).setChecked(filterMode == FILTER_UNREPLACED))
        items.add(UItem.asShadow(null))

        val names = filteredNames()
        val spanCount = gridSpanCount()
        val rows = if (names.isEmpty()) 0 else (names.size + spanCount - 1) / spanCount
        val heightDp = rows * CELL_DP
        items.add(UItem.asCustom(getOrCreateGrid(names), heightDp))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            FILTER_ALL, FILTER_REPLACED, FILTER_UNREPLACED -> {
                if (filterMode == item.id) return
                filterMode = item.id
                listView.adapter.update(true)
            }
        }
    }

    private fun gridSpanCount(): Int {
        val availableWidth = AndroidUtilities.displaySize.x
        return (availableWidth / dp(CELL_DP)).coerceAtLeast(1)
    }

    private fun filteredNames(): List<String> {
        val pack = IconPackStorage.getPack(packId) ?: return emptyList()
        val all = IconPackStorage.replaceableIconNames()
        return when (filterMode) {
            FILTER_REPLACED -> all.filter { it in pack.overriddenNames }
            FILTER_UNREPLACED -> all.filter { it !in pack.overriddenNames }
            else -> all
        }
    }

    private fun getOrCreateGrid(names: List<String>): RecyclerListView {
        gridView?.let {
            gridAdapter?.names = names
            it.layoutManager = GridLayoutManager(context, gridSpanCount())
            gridAdapter?.notifyDataSetChanged()
            return it
        }
        val ctx = context ?: throw IllegalStateException("no context")
        val adapter = IconGridAdapter().also { it.names = names }
        gridAdapter = adapter
        val rv = RecyclerListView(ctx).apply {
            layoutManager = GridLayoutManager(ctx, gridSpanCount())
            this.adapter = adapter
            isNestedScrollingEnabled = false
        }
        // RecyclerListView drives clicks through its own gesture detector at the list level —
        // a plain View.setOnClickListener() on each cell never fires inside it.
        rv.setOnItemClickListener { itemView, position ->
            adapter.names.getOrNull(position)?.let { onIconTapped(it, itemView) }
        }
        gridView = rv
        return rv
    }

    private fun onIconTapped(name: String, anchor: View) {
        val pack = IconPackStorage.getPack(packId)
        val isReplaced = pack != null && name in pack.overriddenNames
        val options = ItemOptions.makeOptions(this, anchor)
        options.add(R.drawable.msg_photos, LocaleController.getString(R.string.InuIconPacksReplace)) {
            launchImagePicker(name)
        }
        if (isReplaced) {
            options.add(R.drawable.msg_reset, LocaleController.getString(R.string.InuIconPacksResetIcon), true) {
                IconPackStorage.removeOverride(packId, name)
                listView.adapter.update(true)
            }
        }
        options.show()
    }

    private fun launchImagePicker(name: String) {
        pendingIconName = name
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
        try {
            startActivityForResult(intent, REQ_PICK_IMAGE)
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    override fun onActivityResultFragment(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_PICK_IMAGE) return
        val name = pendingIconName ?: return
        pendingIconName = null
        val uri = data?.data ?: return
        val ctx = context ?: return
        try {
            ctx.contentResolver.openInputStream(uri)?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                if (bitmap != null) {
                    IconPackStorage.setOverride(packId, name, bitmap)
                    bitmap.recycle()
                    listView.adapter.update(true)
                }
            }
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    private inner class IconGridAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var names: List<String> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return RecyclerListView.Holder(IconCell(parent.context))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder.itemView as IconCell).bind(names[position])
        }

        override fun getItemCount(): Int = names.size
    }

    private inner class IconCell(context: Context) : FrameLayout(context) {
        private val imageView = ImageView(context)
        private val labelView = TextView(context)

        init {
            layoutParams = ViewGroup.LayoutParams(dp(CELL_DP), dp(CELL_DP))
            addView(imageView, org.telegram.ui.Components.LayoutHelper.createFrame(40, 40f, Gravity.CENTER_HORIZONTAL or Gravity.TOP, 0f, 10f, 0f, 0f))
            labelView.textSize = 9f
            labelView.gravity = Gravity.CENTER_HORIZONTAL
            labelView.maxLines = 2
            labelView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourceProvider))
            addView(labelView, org.telegram.ui.Components.LayoutHelper.createFrame(-1, -2f, Gravity.BOTTOM, 4f, 0f, 4f, 6f))
        }

        fun bind(name: String) {
            tag = name
            val override = IconPackStorage.getOverrideDrawable(packId, name, dp(40), dp(40))
            if (override != null) {
                imageView.setImageDrawable(override)
                alpha = 1f
            } else {
                val id = IconPackStorage.resolveDrawableId(name)
                if (id != 0) imageView.setImageResource(id) else imageView.setImageDrawable(null)
                alpha = 0.55f
            }
            labelView.text = name
        }
    }

    private fun dp(value: Int) = AndroidUtilities.dp(value.toFloat())

    companion object {
        private const val FILTER_ALL = 1
        private const val FILTER_REPLACED = 2
        private const val FILTER_UNREPLACED = 3
        private const val CELL_DP = 76
        private const val REQ_PICK_IMAGE = 41001
    }
}
