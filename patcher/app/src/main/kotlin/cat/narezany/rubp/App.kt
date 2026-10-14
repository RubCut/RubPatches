package cat.narezany.rubp

import android.app.Application
import cat.narezany.rubp.work.News
import cat.narezany.rubp.work.Notify

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val words = Strings.of(this)
        Notify.channels(this, words.workChannel, words.newsChannel)
        News.schedule(this)
    }
}
