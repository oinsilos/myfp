package com.fongmi.android.tv.model

import com.fongmi.android.tv.api.SiteApi
import com.fongmi.android.tv.bean.Result
import com.fongmi.android.tv.bean.Site
import com.github.catvod.utils.Trans
import java.util.concurrent.Callable
import java.util.function.Consumer
import java.util.function.Function

/**
 * 多站点聚合搜索轻量入口（Compose 搜索页复用 fongmi 的并发搜索管线，无需 ViewModel）。
 * 与 [ViewModelSearchRunner] 同包，共享其超时/取消语义：start 会自动取消上一轮搜索。
 * 结果回调可能发生在后台线程（runner 用 directExecutor），调用方自行切主线程。
 */
object SearchBox {

    private val runner = ViewModelSearchRunner()

    /** 对全部可搜索站点并发搜索，每个站点出结果回调一次。 */
    @JvmStatic
    fun start(sites: List<Site>, keyword: String, onResult: (Result) -> Unit) {
        val kw = Trans.t2s(keyword)
        runner.start(
            sites,
            Function<Site, Callable<Result>> { site -> Callable { SiteApi.searchContent(site, kw, false, "1") } },
            Consumer { it.run(onResult) },
        )
    }

    @JvmStatic
    fun stop() {
        runner.stop()
    }
}