package com.niuma.checkin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.niuma.core.State

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val app get() = getApplication<CheckinApp>()
    val state = MutableLiveData<State>()
    val error = MutableLiveData<String?>()
    val saved = MutableLiveData<String?>()
    val busy = MutableLiveData(false)
    var page = 0
    var filter = 0
    var recordLimit = 20
    fun runManual() {
        if (busy.value == true) return
        busy.value = true
        app.io.execute {
            try {
                val outcome = DingTalkCheckinFlow(app).execute()
                state.postValue(app.store.transaction { it })
                if (outcome.success) saved.postValue("${outcome.kind?.label ?: ""}打卡成功；${outcome.detail}")
                else error.postValue(outcome.detail)
            } catch (e: IllegalArgumentException) { error.postValue(e.message ?: "签到条件不满足") }
            catch (e: Exception) { error.postValue("签到流程未能启动：${e.message ?: "未知错误"}") }
            finally { busy.postValue(false) }
        }
    }
    fun refresh() = change {
        app.mail.importPrivateConfiguration(app)
        app.mail.ensureBundledConfiguration(app.engine)
    }
    fun change(success: String? = null, action: () -> Unit) {
        busy.value = true
        app.io.execute {
            try {
                action()
                app.reconcile()
                val snapshot = app.store.transaction { it }
                state.postValue(snapshot)
                error.postValue(app.runtimeError)
                if (success != null) saved.postValue(success)
            } catch (e: IllegalArgumentException) { error.postValue(e.message ?: "输入无效") }
            catch (_: Exception) { error.postValue("本地数据读取或保存失败，请重新加载。原有数据未重置。") }
            finally { busy.postValue(false) }
        }
    }
}
