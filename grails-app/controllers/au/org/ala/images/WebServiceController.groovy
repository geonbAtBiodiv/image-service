package au.org.ala.images

import au.org.ala.cas.util.AuthenticationUtils
import grails.converters.JSON
import grails.converters.XML
import org.apache.http.HttpStatus
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.multipart.MultipartRequest

class WebServiceController {

    static allowedMethods = [findImagesByMetadata: 'POST']

    def imageService
    def imageStoreService
    def tagService
    def searchService
    def logService
    def batchService

    def deleteImage() {
        def image = Image.findByImageIdentifier(params.id as String)
        def success = false
        def message = ""
        if (image) {
            success = imageService.scheduleImageDeletion(image.id, AuthenticationUtils.getUserId(request))
            message = "Image scheduled for deletion."
        }
        renderResults(["success": success, message: message])
    }

    private long forEachImageId(closure) {
        def c = Image.createCriteria()
        def imageIdList = c {
            projections {
                property("id")
            }
        }

        long count = 0
        imageIdList.each { imageId ->
            if (closure) {
                closure(imageId)
            }
            count++
        }
        return count
    }

    def scheduleThumbnailGeneration() {
        def imageInstance = Image.findByImageIdentifier(params.id as String)
        def userId = AuthenticationUtils.getUserId(request)
        def results = [success: true]

        if (params.id && !imageInstance) {
            results.success = false
            results.message = "Could not find image ${params.id}"
        } else {
            if (imageInstance) {
                imageService.scheduleThumbnailGeneration(imageInstance.id, userId)
                results.message = "Image thumbnail generation scheduled for image ${imageInstance.id}"
            } else {
                def count = forEachImageId { imageId ->
                    imageService.scheduleThumbnailGeneration(imageId, userId)
                }
                results.message = "Image thumbnail generation scheduled for ${count} images."
            }
        }

        renderResults(results)
    }

    def scheduleArtifactGeneration() {

        def imageInstance = Image.findByImageIdentifier(params.id as String)
        def userId = AuthenticationUtils.getUserId(request)
        def results = [success: true]

        if (params.id && !imageInstance) {
            results.success = false
            results.message = "Could not find image ${params.id}"
        } else {
            if (imageInstance) {
                imageService.scheduleArtifactGeneration(imageInstance.id, userId)
                results.message = "Image artifact generation scheduled for image ${imageInstance.id}"
            } else {
                def count = forEachImageId { imageId ->
                    imageService.scheduleArtifactGeneration(imageId, userId)
                }
                results.message = "Image artifact generation scheduled for ${count} images."
            }
        }

        renderResults(results)
    }

    def scheduleKeywordRegeneration() {
        def imageInstance = Image.findByImageIdentifier(params.id as String)
        def userId = AuthenticationUtils.getUserId(request)
        def results = [success:true]
        if (params.id && !imageInstance) {
            results.success = false
            results.message = "Could not find image ${params.id}"
        } else {
            if (imageInstance) {
                imageService.scheduleKeywordRebuild(imageInstance.id, userId)
                results.message = "Image keyword rebuild scheduled for image ${imageInstance.id}"
            } else {
                def imageList = Image.findAll()
                long count = 0
                imageList.each { image ->
                    imageService.scheduleKeywordRebuild(image.id, userId)
                    count++
                }
                results.message = "Image keyword rebuild scheduled for ${count} images."
            }
        }
        renderResults(results)
    }

    def scheduleInboxPoll() {
        def results = [success:true]
        def userId =  AuthenticationUtils.getUserId(request) ?: params.userId
        results.importBatchId = imageService.schedulePollInbox(userId)
        renderResults(results)
    }

    def getTagModel() {

        def newNode = { Tag tag, String label, boolean disabled = false ->
            [name: label, text: "${label}", children:[], 'icon': false, tagId: tag?.id, state:[disabled: disabled]]
        }

        def rootNode = newNode(null, "root")

        def tags
        if (params.q) {
            def query = params.q.toString().toLowerCase()
            def c = Tag.createCriteria()
            tags = c.list([order: 'asc', sort: 'path']) {
                like("path", "%${query}%")
            }

        } else {
            tags = Tag.list([order: 'asc', sort: 'path'])
        }

        tags.each { tag ->
            def path = tag.path
            if (path.startsWith(TagConstants.TAG_PATH_SEPARATOR)) {
                path = path.substring(TagConstants.TAG_PATH_SEPARATOR.length())
            }
            def bits = path.split(TagConstants.TAG_PATH_SEPARATOR)
            if (bits) {
                def parent = rootNode
                bits.eachWithIndex { pathElement, elementIndex ->
                    def child
                    child = parent.children?.find({ it.name == pathElement})
                    if (!child) {
                        boolean disabled = false
                        if (elementIndex < bits.size() - 1) {
                            disabled = true
                        }
                        child = newNode(tag, pathElement, disabled)
                        parent.children << child
                    }
                    parent = child
                }
            }
        }

        renderResults(rootNode.children)
    }

    def createTagByPath() {
        def success = false
        def tagPath = params.tagPath as String
        def tagId = 0
        if (tagPath) {

            def parent = Tag.get(params.int("parentTagId"))

            def tag = tagService.createTagByPath(tagPath, parent)
            success = tag != null
            tagId = tag.id
        }
        renderResults([success: success, tagId: tagId])
    }

    def moveTag() {
        def success = false

        def target = Tag.get(params.int("targetTagId"))
        def newParent = Tag.get(params.int("newParentTagId"))

        if (target) {

            tagService.moveTag(target, newParent)
        }

        renderResults([success: success])
    }

    def renameTag() {
        def success = false
        def tag = Tag.get(params.int("tagId"))
        if (tag && params.name) {
            tagService.renameTag(tag, params.name)
        }
        renderResults([success: success])
    }

    def deleteTag() {
        def success = false
        def tag = Tag.get(params.int("tagId"))
        if (tag) {
            tagService.deleteTag(tag)
        }
        renderResults([success: success])
    }

    def attachTagToImage() {
        def success = false
        def image = Image.findByImageIdentifier(params.id as String)
        def tag = Tag.get(params.int("tagId"))
        if (image && tag) {
            success = tagService.attachTagToImage(image, tag, AuthenticationUtils.getUserId(request))
        }
        renderResults([success: success])
    }

    def detachTagFromImage() {
        def success = false
        def image = Image.findByImageIdentifier(params.id as String)
        def tag = Tag.get(params.int("tagId"))
        if (image && tag) {
            success = tagService.detachTagFromImage(image, tag)
        }
        renderResults([success: success])
    }

    def getImageInfo() {
        def results = [success:false]
        def image = Image.findByImageIdentifier(params.id as String)
        if (image) {
            results.success = true
            addImageInfoToMap(image, results, params.boolean("includeTags"), params.boolean("includeMetadata"))
        }

        renderResults(results)
    }

    private addImageInfoToMap(Image image, Map results, Boolean includeTags, Boolean includeMetadata) {

        results.height = image.height
        results.width = image.width
        results.tileZoomLevels = image.zoomLevels ?: 0
        results.mimeType = image.mimeType
        results.originalFileName = image.originalFilename
        results.sizeInBytes = image.fileSize
        results.copyright = image.copyright ?: ''
        results.attribution = image.attribution ?: ''
        results.dateUploaded = formatDate(date: image.dateUploaded, format:"yyyy-MM-dd HH:mm:ss")
        results.dateTaken = formatDate(date: image.dateTaken, format:"yyyy-MM-dd HH:mm:ss")
        results.imageUrl = imageService.getImageUrl(image.imageIdentifier)
        results.tileUrlPattern = "${imageService.getImageTilesRootUrl(image.imageIdentifier)}/{z}/{x}/{y}.png"
        results.mmPerPixel = image.mmPerPixel ?: ''
        results.description = image.description ?: ''
        results.copyright = image.copyright ?: ''

        if (includeTags) {
            results.tags = []
            def imageTags = ImageTag.findAllByImage(image)
            imageTags?.each { imageTag ->
                results.tags << imageTag.tag.path
            }
        }

        if (includeMetadata) {
            results.metadata = []
            def metaDataList = ImageMetaDataItem.findAllByImage(image)
            metaDataList?.each { md ->
                results.metadata << [key: md.name, value: md.value, source: md.source]
            }
        }

    }

    def imagePopupInfo() {
        def results = [success:false]

        def image = Image.findByImageIdentifier(params.id as String)
        if (image) {
            results.success = true
            results.data = [:]
            addImageInfoToMap(image, results.data, false, false)
            results.link = createLink(controller: "image", action:'details', id: image.id)
            results.linkText = "Image details..."
            results.title = "Image properties"
        }

        renderResults(results)
    }

    private renderResults(Object results, int responseCode = 200) {

        withFormat {
            json {
                def jsonStr = results as JSON
                if (params.callback) {
                    render("${params.callback}(${jsonStr})")
                } else {
                    render(jsonStr)
                }
            }
            xml {
                render(results as XML)
            }
        }
        response.addHeader("Access-Control-Allow-Origin", "")
        response.status = responseCode
    }

    def getRepositoryStatistics() {
        def results = [:]
        results.imageCount = Image.count()
        results.sizeOnDisk =

        renderResults(results)
    }

    def getRepositorySizeOnDisk() {
        def results = [ repoSizeOnDisk : ImageUtils.formatFileSize(imageStoreService.getRepositorySizeOnDisk()) ]
        renderResults(results)
    }

    def getBackgroundQueueStats() {
        def results = [:]
        results.queueLength = imageService.getImageTaskQueueLength()
        results.tilingQueueLength = imageService.getTilingTaskQueueLength()
        renderResults(results)
    }

    def createSubimage() {
        def image = Image.findByImageIdentifier(params.id as String)
        if (!image) {
            renderResults([success:false, message:"Image not found: ${params.id}"])
            return
        }

        if (!params.x || !params.y || !params.height || !params.width) {
            renderResults([success:false, message:"Rectange not correctly specified. Use x, y, height and width params"])
            return
        }

        def x = params.int('x')
        def y = params.int('y')
        def height = params.int('height')
        def width = params.int('width')

        if (height == 0 || width == 0) {
            renderResults([success:false, message:"Rectange not correctly specified. Height and width cannot be zero"])
            return
        }

        def userId = AuthenticationUtils.getUserId(request)

        def subimage = imageService.createSubimage(image, x, y, width, height, userId)
        renderResults([success: subimage != null, subImageId: subimage?.imageIdentifier])
    }

    def getSubimageRectangles() {

        def image = Image.findByImageIdentifier(params.id as String)
        if (!image) {
            renderResults([success:false, message:"Image not found: ${params.id}"])
            return
        }

        def subimages = Subimage.findAllByParentImage(image)
        def results = [success: true, subimages: []]
        subimages.each { subImageRect ->
            def sub = subImageRect.subimage
            results.subimages << [imageId: sub.imageIdentifier, x: subImageRect.x, y: subImageRect.y, height: subImageRect.height, width: subImageRect.width]
        }
        renderResults(results)
    }

    def addUserMetadataToImage() {
        def image = Image.findByImageIdentifier(params.id as String)
        if (!image) {
            renderResults([success:false, message:"Image not found: ${params.id}"])
            return
        }

        def key = params.key
        if (!key) {
            renderResults([success:false, message:"Metadata key/name not supplied!"])
            return
        }
        def value = params.value
        if (!value) {
            renderResults([success:false, message:"Metadata value not supplied!"])
            return
        }

        def success = imageService.setMetaDataItem(image, MetaDataSourceType.UserDefined, key, value)

        renderResults([success:success])
    }

    def removeUserMetadataFromImage() {
        def image = Image.findByImageIdentifier(params.id as String)
        if (!image) {
            renderResults([success:false, message:"Image not found: ${params.id}"])
            return
        }

        def key = params.key
        if (!key) {
            renderResults([success:false, message:"Metadata key/name not supplied!"])
            return
        }

        def success = imageService.removeMetaDataItem(image, key, MetaDataSourceType.UserDefined)


        renderResults([success: success])
    }

    def getMetadataKeys() {

        def source = params.source as MetaDataSourceType
        def results
        def c = ImageMetaDataItem.createCriteria()

        if (source) {
            results = c.list {
                eq("source", source)
                projections {
                    distinct("name")
                }
            }

        } else {
            results = c.list {
                projections {
                    distinct("name")
                }
            }
        }

        renderResults(results?.sort { it?.toLowerCase() })
    }

    def getImageLinksForMetaDataValues() {

        def key = params.key as String
        if (!key) {
            render([success:false, message:'No key parameter supplied'])
            return
        }

        def query = (params.q ?: params.value) as String

        if (!query) {
            render([success:false, message:'No q or value parameter supplied'])
            return
        }

        def images = searchService.findImagesByMetadata(key, [query], params)
        def results = [images:[], success: true, count: images.totalCount]

        def keyValues = imageService.getMetadataItemValuesForImages(images, key)

        images.each { image ->
            def info =  imageService.getImageInfoMap(image)
            info[key] = keyValues[image.id]
            results.images << info
        }

        renderResults(results)
    }

    def findImagesByOriginalFilename() {
        def query = request.JSON

        if (query) {

            def filenames = query.filenames as List<String>

            if (!filenames) {
                renderResults([success:false, message:'You must supply a list of filenames to search for!'])
                return
            }

            def results =  [:]

            filenames.each { filename ->

                def images = searchService.findImagesByOriginalFilename(filename, params)
                def list = []
                images?.each { image ->
                    def map = imageService.getImageInfoMap(image)
                    list << map
                }
                results[filename] = [count: list.size(), images: list]
            }

            renderResults([success: true, results: results])
            return
        }

        renderResults([success:false, message:'POST with content type "application/JSON" required.'])

    }

    def findImagesByMetadata() {
        def query = request.JSON

        if (query) {

            def key = query.key as String
            def values = query.values as List<String>

            if (!key) {
                renderResults([success:false, message:'You must supply a metadata key!'])
                return
            }

            if (!values) {
                renderResults([success:false, message:'You must supply a values list!'])
                return
            }

            def images = searchService.findImagesByMetadata(key, values, params)
            def results = [:]
            def keyValues = imageService.getMetadataItemValuesForImages(images, key)
            images?.each { image ->
                def map = imageService.getImageInfoMap(image)
                def keyValue = keyValues[image.id]
                def list = results[keyValue]
                if (!list) {
                    list = []
                    results[keyValue] = list
                }
                list << map
            }

            renderResults([success: true, images: results, count:images?.size() ?: 0])
            return
        }

        renderResults([success:false, message:'POST with content type "application/JSON" required.'])
    }

    def getNextTileJob() {
        def results = imageService.createNextTileJob()
        renderResults(results)
    }

    def cancelTileJob() {

        def userId = AuthenticationUtils.getUserId(request)
        def ticket = params.jobTicket ?: params.ticket
        if (!ticket) {
            renderResults([success:false, message:'No job ticket specified'])
            return
        }

        def job = OutsourcedJob.findByTicket(ticket)
        if (!job) {
            renderResults([success:false, message:'No such ticket or ticket expired.'])
            return
        }

        logService.log("Cancelling job (Ticket: ${job.ticket} for image ${job.image.imageIdentifier}")

        // Push the job back on the queue
        if (job.taskType == ImageTaskType.TMSTile) {
            imageService.scheduleTileGeneration(job.image.id, userId)
        }

        job.delete()
    }


    def postJobResults() {
        def ticket = params.jobTicket ?: params.ticket
        if (!ticket) {
            renderResults([success:false, message:'No job ticket specified'])
            return
        }

        def job = OutsourcedJob.findByTicket(ticket)
        if (!job) {
            renderResults([success:false, message:'No such ticket or ticket expired.'])
            return
        }

        def zoomLevels = params.int("zoomLevels")
        if (!zoomLevels) {
            renderResults([success:false, message:'No zoomLevels supplied.'])
            return
        }

        if (job.taskType == ImageTaskType.TMSTile) {
            // Expect a multipart file request
            MultipartFile file = request.getFile('tilesArchive')

            if (!file || file.size == 0) {
                renderResults([success:false, message:'tilesArchive param not present. Expected multipart file.'])
                return
            }

            if (imageStoreService.storeTilesArchiveForImage(job.image.imageIdentifier, file)) {
                job.image.zoomLevels = zoomLevels
                job.delete()
                renderResults([success: true])
                return
            } else {
                renderResults([success:false, message: "Error storing tiles for image!"])
                return
            }
        }
        renderResults([success: false, message:'Unhandled task type'])
    }

    def uploadImage() {
        // Expect a multipart file request

        Image image = null

        def userId = AuthenticationUtils.getUserId(request)
        def url = params.imageUrl ?: params.url

        if (url) {
            // Image is located at an endpoint, and we need to download it first.
            image = imageService.storeImageFromUrl(url, userId)
            if (!image) {
                renderResults([success: false, message: "Unable to retrieve image from ${url}"])
            }
        } else {
            // it should contain a file parameter
            MultipartRequest req = request as MultipartRequest
            if (req) {
                MultipartFile file = req.getFile('image')
                if (!file || file.size == 0) {
                    renderResults([success: false, message: 'image parameter not found, or empty. Please supply an image file.'])
                    return
                }
                image = imageService.storeImage(file, userId)
            } else {
                renderResults([success: false, message: "No url parameter, therefore expected multipart request!"])
            }
        }

        if (image) {

            if (params.metadata) {
                def metadata = JSON.parse(params.metadata as String) as Map
                if (metadata) {
                    metadata.each { kvp ->
                        imageService.setMetaDataItem(image, MetaDataSourceType.SystemDefined, kvp.key as String, kvp.value as String)
                    }
                }
            }

            if (params.tags) {
                def tags = JSON.parse(params.tags as String) as List
                if (tags) {
                    tags.each { String tagPath ->
                        def tag = tagService.createTagByPath(tagPath)
                        tagService.attachTagToImage(image, tag, userId)
                    }
                }
            }

            // Callers have the option to generate thumbs immediately (although it will block). And they will be regenerated later as part of general artifact generation
            // This is useful, though, if the uploader needs to link to the thumbnail straight away
            if (params.synchronousThumbnail) {
                imageService.generateImageThumbnails(image)
            }

            imageService.scheduleArtifactGeneration(image.id, userId)
            renderResults([success: true, imageId: image?.imageIdentifier])
        } else {
            renderResults([success: false, message: "Failed to store image!"])
        }
    }

    def uploadImagesFromUrls() {

        def userId = AuthenticationUtils.getUserId(request)
        def body = request.JSON

        if (body) {

            List<Map<String, String>> imageList = body.images

            if (!imageList) {
                renderResults([success:false, message:'You must supply a list of objects called "images", each of which must contain a "sourceUrl" key, along with optional meta data items!'], HttpStatus.SC_BAD_REQUEST)
                return
            }

            // first create the images
            def results = imageService.batchUploadFromUrl(imageList, userId)

            imageList.each { srcImage ->
                def newImage = results[srcImage.sourceUrl ?: srcImage.imageUrl]
                if (newImage && newImage.success) {
                    imageService.setMetadataItems(newImage.image, srcImage, MetaDataSourceType.SystemDefined, userId)
                    imageService.scheduleArtifactGeneration(newImage.image.id, userId)
                    newImage.image = null
                }
            }

            renderResults([success: true, results: results])
            return
        }

        renderResults([success:false, message:'POST with content type "application/JSON" required.'])
    }

    def calibrateImageScale() {

        def userId = AuthenticationUtils.getUserId(request)
        def image = Image.findByImageIdentifier(params.imageId)
        def units = params.units ?: "mm"
        def pixelLength = params.double("pixelLength") ?: 0
        def actualLength = params.double("actualLength") ?: 0
        if (image && units && pixelLength && actualLength) {
            def pixelsPerMM = imageService.calibrateImageScale(image, pixelLength, actualLength, units, userId)
            renderResults([success: true, message:"Image is scaled at ${pixelsPerMM} pixels per mm"])
            return
        }
        renderResults([success:false, message:'Missing one or more required parameters: imageId, pixelLength, actualLength, units'])
    }

    def scheduleUploadFromUrls() {

        def userId = AuthenticationUtils.getUserId(request)
        def body = request.JSON

        if (body) {
            List<Map<String, String>> imageList = body.images
            if (!imageList) {
                renderResults([success:false, message:'You must supply a list of objects called "images", each of which must contain a "sourceUrl" key, along with optional meta data items!'], HttpStatus.SC_BAD_REQUEST)
                return
            }

            def batchId = batchService.createNewBatch()
            int imageCount = 0
            imageList.each { srcImage ->
                batchService.addTaskToBatch(batchId, new UploadFromUrlTask(srcImage, imageService, userId))
                imageCount++
            }

            renderResults([success: true, message: "${imageCount} urls scheduled for upload (batch id ${batchId}).", batchId: batchId])
            return
        }

        renderResults([success:false, message:'POST with content type "application/JSON" required.'])
    }

    def getBatchStatus() {
        def status = batchService.getBatchStatus(params.batchId)
        if (status) {
            renderResults([success:true, taskCount: status.taskCount, tasksCompleted: status.tasksCompleted, batchId: status.batchId, timeStarted: status.timeStarted.getTime(), timeFinished: status.timeFinished?.getTime() ?: 0])
            return
        }

        renderResults([success:false, message:'Missing or invalid batchId'])
    }

}
