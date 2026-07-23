package com.nextquest.app

import android.app.Application
import com.nextquest.app.data.NextQuestDb
import com.nextquest.app.data.QuestRepository

class NextQuestApp : Application() {
    val db: NextQuestDb by lazy { NextQuestDb.build(this) }
    val repository: QuestRepository by lazy { QuestRepository(db.questDao(), db.eventLogDao()) }
}
