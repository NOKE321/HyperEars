package dev.hyperears.presentation

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.Drawable
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.RemoteException
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.app.Dialog
import dev.hyperears.bridge.ModuleContract
import dev.hyperears.bridge.NativePresentationContract
import dev.hyperears.hook.HookContext
import dev.hyperears.hook.ModuleLog
import dev.hyperears.hook.maskBluetoothAddress
import dev.hyperears.integration.HeadsetArtwork
import dev.hyperears.integration.EarbudState
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.IdentityHashMap

/** Owns explicitly authorized native windows and local display bindings, never device protocols. */
internal abstract class NativeWindowBridge(
    protected val context: Context,
    private val forBinding: (NativeWindowRequest) -> NativeWindowRequest?,
    private val onOpened: (String, IBinder) -> Unit,
    private val onClosed: (String) -> Unit,
    private val openSettings: (Context, EarbudState) -> Boolean,
) : HookContext() {
    protected val handler = Handler(Looper.getMainLooper())
    private val controllers = IdentityHashMap<Any, Binding>()
    private val activities = IdentityHashMap<Any, Binding>()
    private val creating = ThreadLocal<Binding?>()
    private val consumedTickets = mutableSetOf<String>()
    private val artwork = NativeArtworkLoader(context)
    protected lateinit var controllerType: Class<*>
    protected lateinit var callbackType: Class<*>
    private lateinit var serviceType: Class<*>
    private lateinit var resourceInitializer: Method
    private var enabled = false

    protected class Binding(
        var request: NativeWindowRequest,
        val controller: Any,
        val activity: Activity,
        val connection: ServiceConnection,
    ) {
        val state: EarbudState get() = request.state
        var active = true
        var closing = false
        var bound = false
        var callback: Any? = null
        var navigation: ContentNavigation? = null
        lateinit var binder: Binder
        lateinit var death: IBinder.DeathRecipient
    }

    protected class ContentNavigation(val view: View) {
        private val clickable = view.isClickable
        private val focusable = view.isFocusable

        fun release() {
            view.setOnClickListener(null)
            view.isClickable = clickable
            view.isFocusable = focusable
        }
    }

    protected abstract val serviceContract: NativeWindowServiceContract
    protected abstract fun resolveSymbols(): NativeWindowSymbols
    protected abstract fun installModelHooks()
    protected abstract fun synchronizeModel(binding: Binding)
    protected open fun prepareNativeCallers() = Unit
    protected open fun afterCreate(binding: Binding) = Unit
    protected open fun destroyNative(binding: Binding, proceed: () -> Any?): Any? = proceed()
    protected open fun destroyRejected(controller: Any): Any? = null
    protected open fun onReleased(binding: Binding) = Unit
    protected open fun twsLayout(binding: Binding): Boolean = !projection(binding).singleBattery

    protected open fun installInitializationHooks(init: Method) {
        hookBefore(init) { bindingFor(instance)?.let(::synchronizeModel) }
        hookAfter(init) { bindingFor(instance)?.let(::updateArtwork) }
    }

    protected fun bindingFor(controller: Any?): Binding? = controllers[controller]
    protected fun creatingBinding(): Binding? = creating.get()
    protected fun bindingForInfo(info: Any?, field: String): Binding? = controllers.values.firstOrNull {
        it.active && NativeMembers.get(it.controller, field) === info
    }

    protected fun authorizedRequest(activity: Activity): NativeWindowRequest? =
        if (!enabled) null else runCatching { NativeWindowRequest.read(activity)?.let(forBinding) }.getOrNull()

    final override fun install() {
        val symbols = resolveSymbols()
        controllerType = symbols.controller
        serviceType = symbols.service
        callbackType = symbols.callback
        val create = symbols.create
        val destroy = symbols.destroy
        val load = symbols.load
        val init = symbols.init
        resourceInitializer = symbols.resourceInit
        val dynamic = symbols.dynamic
        prepareNativeCallers()

        module.hook(create).intercept { chain ->
            val controller = requireNotNull(chain.thisObject)
            val activity = NativeMembers.get(controller, "mActivityContext") as Activity
            if (!activity.intent.hasExtra(NativePresentationContract.EXTRA_WINDOW)) return@intercept chain.proceed()
            if (!enabled) {
                activity.finish()
                return@intercept null
            }
            val request = authorizedRequest(activity)
            if (request == null || request.kind != symbols.kind || !consumedTickets.add(request.id)) {
                ModuleLog.warn(TAG, "rejected invalid native window authorization")
                activity.finish()
                return@intercept null
            }
            val device = activity.intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            if (device == null || device.address != request.state.address ||
                BluetoothDevice::class.java.getMethod("isConnected").invoke(device) != true) {
                activity.finish()
                return@intercept null
            }
            val binding = Binding(request, controller, activity,
                NativeMembers.get(controller, "mFastConnectServiceConnection") as ServiceConnection)
            binding.binder = localService(binding)
            binding.death = IBinder.DeathRecipient { handler.post { close(binding, "producer died") } }
            // Keep the display binding through every failure path, so destroy can never fall
            // through to an OEM protocol lifecycle after a partially initialized module window.
            controllers[controller] = binding
            activities[activity] = binding
            runCatching { request.producer.linkToDeath(binding.death, 0) }.getOrElse {
                binding.closing = true
                activity.finish()
                return@intercept null
            }
            val previous = creating.get()
            val originalDynamic = dynamic.getBoolean(null)
            creating.set(binding)
            try {
                chain.proceed().also {
                    check(!activity.isFinishing) { "Native window rejected its display parameters" }
                    afterCreate(binding)
                    check(binding.active && !binding.closing && !activity.isFinishing) { "Native display closed during initialization" }
                    onOpened(request.id, binding.binder)
                    ModuleLog.debug(TAG, "created address=${maskBluetoothAddress(request.state.address)} " +
                        "revision=${request.state.revision} single=${projection(binding).singleBattery}")
                    scheduleArtworkTuning(binding)
                }
            } catch (error: Throwable) {
                binding.closing = true
                activity.finish()
                ModuleLog.warn(TAG, "native window initialization failed", error)
                null
            } finally {
                dynamic.setBoolean(null, originalDynamic)
                if (previous == null) creating.remove() else creating.set(previous)
            }
        }

        // The Controller uses an in-process display service. Only its exact Activity/connection
        // pair is intercepted; no real FastConnect service, BLE scanner, or global Binder is used.
        hookBefore(ContextWrapper::class.java.getMethod("bindService", Intent::class.java,
            ServiceConnection::class.java, Int::class.javaPrimitiveType)) {
            val binding = activities[instance] ?: return@hookBefore
            val intent = args[0] as? Intent ?: return@hookBefore
            if (args[1] !== binding.connection || intent.component?.className != "$PREFIX.MiuiFastConnectService") return@hookBefore
            binding.bound = true
            handler.post {
                if (binding.active && binding.bound) binding.connection.onServiceConnected(
                    ComponentName(ModuleContract.NATIVE_HEADSET_PACKAGE, "$PREFIX.MiuiFastConnectService"),
                    binding.binder,
                )
            }
            result = true
        }
        hookBefore(ContextWrapper::class.java.getMethod("unbindService", ServiceConnection::class.java)) {
            val binding = activities[instance] ?: return@hookBefore
            if (args[0] !== binding.connection || !binding.bound) return@hookBefore
            binding.bound = false
            result = null
        }

        hookBefore(load) {
            val binding = controllers[instance] ?: return@hookBefore
            initializeStaticResources(binding)
            result = true
        }
        installInitializationHooks(init)

        module.hook(destroy).intercept { chain ->
            val binding = controllers[chain.thisObject]
            try {
                if (binding != null) {
                    destroyNative(binding) { chain.proceed() }
                } else {
                    val owner = requireNotNull(chain.thisObject)
                    val activity = NativeMembers.get(owner, "mActivityContext") as? Activity
                    if (activity?.intent?.hasExtra(NativePresentationContract.EXTRA_WINDOW) == true) {
                        destroyRejected(owner)
                    } else chain.proceed()
                }
            } finally { binding?.let(::release) }
        }
        installModelHooks()
        installContentNavigation(symbols.contentReady)
        installImageViewHook()
        enabled = true
        ModuleLog.debug(TAG, "native window semantic hooks ready")
    }

    /** 图被塞进 ImageView 的瞬间就缩放，抢在任何一帧绘制之前。 */
    private fun installImageViewHook() {
        runCatching {
            val setter = NativeMembers.method(
                ImageView::class.java, "setImageDrawable", Drawable::class.java)
            hookAfter(setter) {
                val view = instance as? ImageView ?: return@hookAfter
                val drawable = args[0] as? Drawable ?: return@hookAfter
                if (drawable is AutoStartDrawableWrapper) {
                    runCatching { applyScale(view, ARTWORK_SCALE) }
                }
            }
        }.onFailure { ModuleLog.warn(TAG, "artwork setImageDrawable hook failed", it) }
    }

    private fun installContentNavigation(createDialog: Method) {
        runCatching {
            val content = NativeMembers.field(controllerType, "mView")
            require(View::class.java.isAssignableFrom(content.type))
            hookAfter(createDialog) {
                val binding = controllers[instance] ?: return@hookAfter
                if (!binding.active || binding.closing || binding.activity.isFinishing) return@hookAfter
                runCatching {
                    val view = content.get(binding.controller) as? View ?: return@runCatching
                    if (binding.navigation?.view === view) return@runCatching
                    binding.navigation?.release()
                    binding.navigation = null
                    // Only the authorized Controller's content is clickable. Native dialog controls
                    // (including close/retry) retain their own actions; an existing root action wins.
                    if (view.hasOnClickListeners()) return@runCatching
                    binding.navigation = ContentNavigation(view)
                    view.setOnClickListener {
                        if (!binding.active || binding.closing || binding.activity.isFinishing ||
                            !binding.request.producer.isBinderAlive) return@setOnClickListener
                        runCatching { openSettings(binding.activity, binding.state) }
                            .onSuccess { if (it) close(binding, "settings opened") }
                            .onFailure { ModuleLog.warn(TAG, "unable to open headset settings", it) }
                    }
                    view.isFocusable = true
                }.onFailure { ModuleLog.warn(TAG, "native content navigation unavailable", it) }
            }
        }.onFailure { ModuleLog.warn(TAG, "native content navigation hook unavailable", it) }
    }

    protected fun installBatteryLayout(layout: NativePopupBatteryLayout) {
        hookAfter(layout.render) {
            val binding = controllers[instance] ?: return@hookAfter
            if (!binding.active || binding.closing || binding.activity.isFinishing) return@hookAfter
            runCatching {
                val root = args[0] as? View ?: return@runCatching
                if (root !== NativeMembers.get(binding.controller, "mView")) return@runCatching
                val p = projection(binding)
                wrapDialogHeight(pairingDialog(binding))
                runCatching { (root as? ViewGroup)?.let { tuneArtwork(it, ARTWORK_SCALE) } }
                if (layout.apply(root, p)) {
                    ModuleLog.debug(TAG) {
                        "battery layout updated in place single=${p.singleBattery} " +
                            "case=${p.showCaseInPopup} revision=${binding.state.revision}"
                    }
                }
            }.onFailure {
                ModuleLog.warn(TAG, "native battery layout incompatible", it)
                close(binding, "battery layout unavailable")
            }
        }
    }

    fun update(state: EarbudState, session: String, metadata: NativePresentationMetadata, producer: IBinder) {
        controllers.values.toList().filter { it.state.address == state.address }.forEach { binding ->
            if (!binding.active || binding.closing || binding.activity.isFinishing) return@forEach
            val updated = binding.request.updated(state, session, metadata, producer)
            if (updated == null) {
                close(binding, "session ended or ownership changed")
            } else if (state.revision > binding.state.revision) {
                val previous = projection(binding)
                binding.request = updated
                val p = projection(binding)
                // Keep the existing View, Dialog, animation state and close/navigation listeners.
                synchronizeModel(binding)
                if (previous.artwork != p.artwork || previous.form != p.form) updateArtwork(binding)
                runCatching { publishBattery(binding) }.onFailure { close(binding, "native update failed") }
                ModuleLog.debug(TAG, "updated address=${maskBluetoothAddress(p.address)} revision=${state.revision}")
            }
        }
    }

    fun closeAll(reason: String) = controllers.values.toList().forEach { close(it, reason) }

    protected open fun publishBattery(binding: Binding) {
        val p = projection(binding)
        binding.callback?.let { callback ->
            callbackType.getMethod("deviceBatteryStateUpdate", String::class.java,
                Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(callback, p.address, true, charging(p),
                    p.left.percent ?: -1, p.right.percent ?: -1, p.chargingCase.percent ?: -1)
        }
    }

    private fun localService(binding: Binding): Binder {
        val binder = Binder()
        val proxy = Proxy.newProxyInstance(serviceType.classLoader, arrayOf(serviceType)) { instance, method, args ->
            when (method.name) {
                "asBinder" -> binder
                "equals" -> instance === args?.get(0)
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "HyperEars.NativeWindow"
                serviceContract.register -> { if (binding.active) binding.callback = args?.get(0); null }
                serviceContract.unregister -> { binding.callback = null; null }
                serviceContract.connectedState -> serviceContract.connectionState(binding.state.address, binding.active, args)
                serviceContract.foreground -> {
                    if (args?.get(0) == false) handler.post { close(binding, "background") }
                    null
                }
                in serviceContract.ignoredResourceCalls -> null
                else -> {
                    handler.post { close(binding, "unsupported service call ${method.name}") }
                    throw RemoteException("Unsupported native display operation")
                }
            }
        } as IInterface
        binder.attachInterface(proxy, serviceType.name)
        return binder
    }

    protected fun projection(binding: Binding) = requireNotNull(NativeHeadsetProjection.from(binding.state))

    protected fun initializeStaticResources(binding: Binding) {
        val owner = binding.controller
        val record = requireNotNull(NativeMembers.get(owner, "mCloudResRecord"))
        NativeMembers.set(record, "mLayout", 1)
        NativeMembers.set(record, "mTwsHeadset", twsLayout(binding))
        NativeMembers.set(record, "mAllowSync", false)
        NativeMembers.set(record, "mDeviceName", projection(binding).name)
        NativeMembers.set(record, "mRecordDocument", null)
        val resources = requireNotNull(NativeMembers.get(owner, "mLocalResource"))
        if (NativeMembers.call(resources, "isInit") == true) NativeMembers.call(resources, "cleanUp")
        resourceInitializer.invoke(resources,
            "", "", false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 800, false, false)
    }

    protected fun updateArtwork(binding: Binding) {
        val p = projection(binding)
        val defaults = HeadsetArtwork.defaultFor(p.form)
        @Suppress("UNCHECKED_CAST")
        val icons = NativeMembers.get(binding.controller, "mHeadsetIconDrawable") as Array<Drawable?>
        icons.indices.forEach { icons[it] = artwork.load(p.artwork.headset, defaults.headset) }
        NativeMembers.set(binding.controller, "mHeadsetBoxIconDrawable",
            artwork.load(p.artwork.chargingCase ?: HeadsetArtwork.EARBUDS.chargingCase!!,
                HeadsetArtwork.EARBUDS.chargingCase!!))
    }

    /** 本地私有构建：放大弹窗里的耳机图（系统槽位固定为正方形）。 */
    private fun scheduleArtworkTuning(binding: Binding) {
        val dialog = pairingDialog(binding)
        runCatching { dialog?.window?.setWindowAnimations(0) }
        wrapDialogHeight(dialog)
        val root = (dialog?.window?.decorView as? ViewGroup)
            ?: (binding.activity.window?.decorView as? ViewGroup)
            ?: return
        // 绘制前应用，避免首帧先显示小图再跳大；只在前 1.5 秒监听，
        // 之后交给渲染回调触发，避免每帧都去改布局。
        val deadline = android.os.SystemClock.uptimeMillis() + 1500L
        root.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                runCatching { tuneArtwork(root, ARTWORK_SCALE) }.onFailure {
                    ModuleLog.warn(TAG, "artwork preDraw failed", it)
                }
                if (android.os.SystemClock.uptimeMillis() >= deadline) {
                    runCatching { root.viewTreeObserver.removeOnPreDrawListener(this) }
                }
                return true
            }
        })
        for (delay in longArrayOf(120L, 400L, 900L, 1600L)) {
            root.postDelayed({
                runCatching { tuneArtwork(root, ARTWORK_SCALE) }.onFailure {
                    ModuleLog.warn(TAG, "artwork tuning failed", it)
                }
            }, delay)
        }
        root.postDelayed({ runCatching { dumpTree(root) } }, 700L)
    }

    private fun tuneArtwork(root: ViewGroup, scale: Float) {
        if (root.width < 400) return
        val seen = StringBuilder()
        fun visit(view: View) {
            if (view is ImageView) {
                seen.append(view.width).append('x').append(view.height).append(' ')
                val square = view.width == view.height && view.width >= 200
                if (square || view.drawable is AutoStartDrawableWrapper) applyScale(view, scale)
            }
            if (view is ViewGroup) {
                val name = if (view.id != View.NO_ID) {
                    runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
                } else null
                if (name != null && name in SHRINK_IDS) {
                    val params = view.layoutParams
                    if (params != null && params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                        params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        view.layoutParams = params
                        view.minimumHeight = 0
                        ModuleLog.debug(TAG, "artwork tuning: shrink $name")
                    }
                }
                for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
        }
        visit(root)
        ModuleLog.debug(TAG, "artwork tuning: images=[$seen]")
    }

    private fun pairingDialog(binding: Binding): Dialog? = runCatching {
        NativeMembers.field(binding.activity.javaClass, "mPairingAlert").get(binding.activity) as? Dialog
    }.getOrNull()

    /**
     * 宿主的弹窗窗口高度是写死的一整块（约半屏），内容只占其中一部分，
     * 于是图片上下就是大片空白。把窗口高度改成 wrap_content，让卡片贴着内容走。
     */
    private fun wrapDialogHeight(dialog: Dialog?) {
        val window = dialog?.window ?: return
        runCatching {
            val attributes = window.attributes
            if (attributes.height > 0) {
                window.setLayout(attributes.width, ViewGroup.LayoutParams.WRAP_CONTENT)
                ModuleLog.debug(TAG, "artwork tuning: window height wrap (was ${attributes.height})")
            }
        }.onFailure { ModuleLog.warn(TAG, "window height override failed", it) }
    }

    /** 一次性打印弹窗视图树，便于按 id/文字精确定位要调整的行。 */
    private fun dumpTree(root: ViewGroup) {
        val builder = StringBuilder()
        fun visit(view: View, depth: Int) {
            if (depth > 14) return
            repeat(depth) { builder.append('.') }
            val name = if (view.id != View.NO_ID) {
                runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
            } else null
            builder.append(view.javaClass.simpleName)
            if (name != null) builder.append('#').append(name)
            builder.append(' ').append(view.width).append('x').append(view.height)
            if (view.visibility != View.VISIBLE) builder.append(" vis=").append(view.visibility)
            (view as? android.widget.TextView)?.text?.let { if (it.isNotEmpty()) builder.append(" \"").append(it).append('"') }
            builder.append('\n')
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), depth + 1)
        }
        visit(root, 0)
        ModuleLog.debug(TAG, "artwork tuning: tree\n$builder")
    }

    private fun applyScale(view: ImageView, scale: Float) {
        if (view.width > 0 && view.height > 0) {
            view.pivotX = view.width / 2f
            view.pivotY = view.height / 2f
        }
        view.scaleX = scale
        view.scaleY = scale
        var parent = view.parent
        while (parent is ViewGroup) {
            parent.clipChildren = false
            parent.clipToOutline = false
            parent = parent.parent
        }
        ModuleLog.debug(TAG, "artwork tuning: scaled ${view.width} by $scale")
    }

    protected fun charging(p: NativeHeadsetProjection): Int =
        (if (p.left.charging) 1 else 0) or (if (p.right.charging) 2 else 0) or
            (if (p.chargingCase.charging) 4 else 0)

    protected fun setInt(owner: Any, method: String, value: Int) =
        NativeMembers.method(owner.javaClass, method, Int::class.javaPrimitiveType!!).invoke(owner, value)

    protected fun close(binding: Binding, reason: String) {
        if (!binding.active || binding.closing || binding.activity.isFinishing) return
        binding.closing = true
        binding.navigation?.release()
        binding.navigation = null
        ModuleLog.debug(TAG, "close address=${maskBluetoothAddress(binding.state.address)} reason=$reason")
        val closed = binding.callback?.let {
            runCatching {
                if (serviceContract.closeWithAddress) callbackType.getMethod(serviceContract.close, String::class.java).invoke(it, binding.state.address)
                else callbackType.getMethod(serviceContract.close).invoke(it)
            }.isSuccess
        } == true
        if (!closed) binding.activity.finish()
    }

    private fun release(binding: Binding) {
        if (!binding.active) return
        binding.active = false
        binding.navigation?.release()
        binding.navigation = null
        binding.callback = null
        binding.bound = false
        controllers.remove(binding.controller)
        activities.remove(binding.activity)
        runCatching { onReleased(binding) }.onFailure { ModuleLog.warn(TAG, "native display cleanup failed", it) }
        runCatching { binding.request.producer.unlinkToDeath(binding.death, 0) }
        runCatching { artwork.stopAnimations() }
        onClosed(binding.request.id)
        ModuleLog.debug(TAG, "released address=${maskBluetoothAddress(binding.state.address)}")
    }

    companion object {
        private const val ARTWORK_SCALE = 1.05f

        /** 宿主的这几个容器带固定高度，是弹窗下方那片空白的主要来源。 */
        private val SHRINK_IDS = setOf(
            "pairingCustom",
            "pairingContentView",
            "groupDialog",
            "groupConnectSuccess",
            "imageViewHeadsetLayout",
            "imageViewBoxLayout",
            "textViewHeadsetBattery",
            "textViewBoxBattery",
        )

        const val PREFIX = "com.android.bluetooth.ble.app.fastconnect"
        private const val TAG = "NativeWindow"
    }
}
