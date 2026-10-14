package de.uni.youtubeaa.car.hybrid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Representative response shapes, not captures from a signed-in user's account. */
class InnerTubeFeedParserTest {
    private fun videos(json: String) = InnerTubeFeedParser.videos(JSONObject(json))
    private fun tokens(json: String) = InnerTubeFeedParser.continuations(JSONObject(json))

    @Test fun modernLockupIncludesTitleChannelAndBestImage() {
        val cards = videos("""{
  "contents": [
    {
      "richItemRenderer": {
        "content": {
          "lockupViewModel": {
            "contentId": "newVideo001",
            "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
            "contentImage": {
              "thumbnailViewModel": {
                "image": {
                  "sources": [
                    {
                      "url": "//img.test/small",
                      "width": 120,
                      "height": 90
                    },
                    {
                      "url": "//img.test/large",
                      "width": 640,
                      "height": 360
                    }
                  ]
                }
              }
            },
            "metadata": {
              "lockupMetadataViewModel": {
                "title": {
                  "content": "Neu erschienen"
                },
                "metadata": {
                  "contentMetadataViewModel": {
                    "metadataRows": [
                      {
                        "metadataParts": [
                          {
                            "text": {
                              "content": "Mein Kanal · 2 Stunden",
                              "commandRuns": [
                                {
                                  "startIndex": 0,
                                  "length": 10,
                                  "onTap": {
                                    "innertubeCommand": {
                                      "browseEndpoint": {
                                        "browseId": "UCchannel"
                                      }
                                    }
                                  }
                                }
                              ]
                            }
                          }
                        ]
                      }
                    ]
                  }
                }
              }
            }
          }
        }
      }
    }
  ]
}""")
        assertEquals(1, cards.size)
        assertEquals("Neu erschienen", cards[0].title)
        assertEquals("Mein Kanal", cards[0].channel)
        assertEquals("UCchannel", cards[0].channelId)
        assertEquals("https://img.test/large", cards[0].thumbnailUrl)
    }

    @Test fun androidContextCardsAndClassicCardsAreBothIncluded() {
        val cards = videos("""{"contents":[
          {"videoWithContextRenderer":{"videoId":"newVideo002","title":{"runs":[{"text":"Android"}]},"shortBylineText":{"simpleText":"Kanal"}}},
          {"videoRenderer":{"videoId":"newVideo003","title":{"simpleText":"Klassisch"}}}
        ]}""")
        assertEquals(listOf("newVideo002", "newVideo003"), cards.map { it.id })
        assertEquals("Kanal", cards[0].channel)
    }

    @Test fun tvTilesRetainMetadataDespiteIgnoredHeaderAndMenuBranches() {
        val cards = videos("""{"contents":[{"tileRenderer":{"contentId":"newVideo004",
          "header":{"tileHeaderRenderer":{"thumbnail":{"thumbnails":[{"url":"//img.test/tv"}]}}},
          "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"TV"},
            "lines":[{"lineRenderer":{"items":[{"lineItemRenderer":{"text":{"simpleText":"TV Kanal"}}}]}}]}}
        }}]}""")
        assertEquals("TV", cards.single().title)
        assertEquals("TV Kanal", cards.single().channel)
        assertEquals("https://img.test/tv", cards.single().thumbnailUrl)
    }

    @Test fun shortsSupportDirectAndWrappedEndpoints() {
        val cards = videos("""{"contents":[
          {"shortsLockupViewModel":{"onTap":{"reelWatchEndpoint":{"videoId":"newShort001"}},"overlayMetadata":{"primaryText":{"content":"Direkt"}}}},
          {"shortsLockupViewModel":{"onTap":{"innertubeCommand":{"reelWatchEndpoint":{"videoId":"newShort002"}}},"overlayMetadata":{"primaryText":{"content":"Wrapper"}}}}
        ]}""")
        assertEquals(listOf("newShort001", "newShort002"), cards.map { it.id })
        assertEquals("Wrapper", cards[1].title)
    }

    @Test fun ignoresUnselectedTabsAdsMenusAndNonVideoLockups() {
        val cards = videos("""{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[
          {"tabRenderer":{"selected":false,"content":{"videoRenderer":{"videoId":"hiddenVid01"}}}},
          {"tabRenderer":{"selected":true,"content":{"items":[
            {"videoRenderer":{"videoId":"newVideo005","menu":{"videoRenderer":{"videoId":"hiddenVid02"}}}},
            {"adSlotRenderer":{"videoRenderer":{"videoId":"hiddenVid03"}}},
            {"lockupViewModel":{"contentId":"playlist001","contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST"}}
          ]}}}
        ]}},"header":{"videoRenderer":{"videoId":"hiddenVid04"}}}""")
        assertEquals(listOf("newVideo005"), cards.map { it.id })
    }

    @Test fun duplicatesEnrichMissingMetadataWithoutReordering() {
        val cards = videos("""{"contents":[
          {"videoRenderer":{"videoId":"newVideo006"}},
          {"videoRenderer":{"videoId":"newVideo007","title":{"simpleText":"Zweiter"}}},
          {"gridVideoRenderer":{"videoId":"newVideo006","title":{"simpleText":"Erster"}}}
        ]}""")
        assertEquals(listOf("newVideo006", "newVideo007"), cards.map { it.id })
        assertEquals("Erster", cards.first().title)
    }

    @Test fun mainFeedContinuationOutranksShortsShelf() {
        val result = tokens("""{"contents":{"richGridRenderer":{"contents":[
          {"richSectionRenderer":{"content":{"richShelfRenderer":{"contents":[
            {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"SHORTS"}}}}
          ]}}}},
          {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"MAIN"}}}}
        ]}}}""")
        assertEquals(listOf("MAIN"), result)
    }

    @Test fun modernContinuationAndAppendResponsesAreSupported() {
        val json = """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[
          {"videoWithContextRenderer":{"videoId":"newVideo008"}},
          {"continuationItemViewModel":{"continuationCommand":{"token":"NEXT"}}}
        ]}}]}"""
        assertEquals(listOf("NEXT"), tokens(json))
        assertEquals("newVideo008", videos(json).single().id)
    }

    @Test fun legacyContinuationContentsAndSelectedTabAreSupported() {
        assertEquals(listOf("TV_NEXT"), tokens("""{"continuationContents":{"gridContinuation":{
          "items":[],"continuations":[{"nextContinuationData":{"continuation":"TV_NEXT"}}]}}}"""))
        assertEquals(listOf("SELECTED"), tokens("""{"contents":{"tabs":[
          {"tabRenderer":{"selected":false,"content":{"continuations":[{"nextContinuationData":{"continuation":"WRONG"}}]}}},
          {"tabRenderer":{"selected":true,"content":{"continuations":[{"nextContinuationData":{"continuation":"SELECTED"}}]}}}
        ]}}"""))
    }

    @Test fun malformedIdsNeverBecomeVideos() {
        assertTrue(videos("""{"contents":[{"tileRenderer":{"contentId":"ad-tracking-id-too-long"}},
          {"videoRenderer":{"videoId":""}}]}""").isEmpty())
    }
}
