package my.torrstream.app.tmdb.models.content_ratings

data class ContentRatingsModel(
    val id: Int,
    val results: List<Result>?
)