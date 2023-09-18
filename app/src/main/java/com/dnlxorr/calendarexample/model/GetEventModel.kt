package com.dnlxorr.calendarexample.model

data class GetEventModel(
    var id: Int = 0,
    var summary: String? = "",
    var startDate: String = "",
)
