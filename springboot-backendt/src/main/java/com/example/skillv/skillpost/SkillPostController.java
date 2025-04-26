package com.example.skillv.skillpost;

import com.example.skillv.comment.Comment;
import com.example.skillv.comment.CommentMapper;
import com.example.skillv.comment.CommentRequest;
import com.example.skillv.notification.NotificationDto;
import com.example.skillv.notification.NotificationMapper;
import com.example.skillv.notification.NotificationService;
import com.example.skillv.notification.NotificationType;
import com.example.skillv.storage.AzureBlobStorageService;
import com.example.skillv.user.User;
import com.example.skillv.user.UserService;
import com.example.skillv.util.VideoThumbnailService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.coyote.BadRequestException;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionAuthenticatedPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/skill-posts")
@RequiredArgsConstructor
public class SkillPostController {

    private final SkillPostService skillPostService;
    private final UserService userService;
    private final SkillPostMapper skillPostMapper;
    private final CommentMapper commentMapper;
    private final NotificationMapper notificationMapper;
    private final AzureBlobStorageService storageService;
    private final NotificationService notificationService;
    private final VideoThumbnailService thumbnailService;

    private static final int MAX_VIDEO_DURATION_SECONDS = 30;
    private static final int MAX_IMAGES = 3;
    private static final String[] ALLOWED_IMAGE_TYPES = {"image/jpeg", "image/png", "image/gif"};
    private static final String[] ALLOWED_VIDEO_TYPES = {"video/mp4", "video/mpeg", "video/quicktime"};

    private void validateFileType(MultipartFile file, String[] allowedTypes) throws BadRequestException {
        String contentType = file.getContentType();

        // Check if content type is among allowed types
        boolean allowed = false;
        for (String type : allowedTypes) {
            if (type.equals(contentType)) {
                allowed = true;
                break;
            }
        }

        if (!allowed) {
            throw new BadRequestException("File type '" + contentType + "' is not allowed");
        }
    }

    @PostMapping(consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
    public ResponseEntity<SkillPostDto> saveSkillPost(
            @AuthenticationPrincipal OAuth2IntrospectionAuthenticatedPrincipal principal,
            @RequestParam("skillName") String skillName,
            @RequestParam("title") String title,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "images", required = false) MultipartFile[] images,
            @RequestParam(value = "video", required = false) MultipartFile video,
            @RequestParam(value = "videoDurationSeconds", required = false) Integer videoDurationSeconds
    ) {
        try {
            // Create request object
            SkillPostRequest request = new SkillPostRequest();
            request.setSkillName(skillName);
            request.setTitle(title);
            request.setDescription(description);
            request.setVideoDurationSeconds(videoDurationSeconds);

            // Validate video duration if video is provided
            if (video != null && !video.isEmpty()) {
                if (videoDurationSeconds == null) {
                    throw new BadRequestException("Video duration must be provided for videos");
                }

                if (videoDurationSeconds > MAX_VIDEO_DURATION_SECONDS) {
                    throw new BadRequestException("Video duration cannot exceed " + MAX_VIDEO_DURATION_SECONDS + " seconds");
                }

                validateFileType(video, ALLOWED_VIDEO_TYPES);

                // Upload video to Azure Blob Storage
                String videoUrl = storageService.uploadFile(video, video.getOriginalFilename());
                request.setSkillPostVideoUrl(videoUrl);

                // Generate and set video thumbnail
                String thumbnailUrl = thumbnailService.generateAndUploadThumbnail(video, videoDurationSeconds);
                request.setSkillPostVideoThumbnailUrl(thumbnailUrl);
            }

            // Process images if provided
            if (images != null && images.length > 0) {
                if (images.length > MAX_IMAGES) {
                    throw new BadRequestException("Maximum " + MAX_IMAGES + " images allowed");
                }

                ArrayList<String> imageUrls = new ArrayList<>();

                for (MultipartFile image : images) {
                    validateFileType(image, ALLOWED_IMAGE_TYPES);
                    String imageUrl = storageService.uploadFile(image, image.getOriginalFilename());
                    imageUrls.add(imageUrl);
                }

                request.setSkillPostImageUrls(imageUrls);
            }

            // Convert request to entity
            SkillPost skillPost = skillPostMapper.toEntity(request);

            // Set author details
            String googleId = principal.getAttributes().get("sub").toString();
            var user = userService.findByGoogleId(googleId);

            skillPost.setAuthorId(user.getId().toString());
            skillPost.setAuthorProfileImageUrl(user.getProfileImageUrl());
            skillPost.setAuthorName(principal.getName());

            // Save and convert to DTO
            SkillPost savedPost = skillPostService.save(skillPost);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(skillPostMapper.toDto(savedPost));
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file", e);
        }
    }

    @PutMapping(value = "/{skill-post-id}", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
    public ResponseEntity<SkillPostDto> updateSkillPost(
            @PathVariable("skill-post-id") String skillPostId,
            @AuthenticationPrincipal OAuth2IntrospectionAuthenticatedPrincipal principal,
            @RequestParam("skillName") String skillName,
            @RequestParam("title") String title,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "images", required = false) MultipartFile[] images,
            @RequestParam(value = "video", required = false) MultipartFile video,
            @RequestParam(value = "videoDurationSeconds", required = false) Integer videoDurationSeconds,
            @RequestParam(value = "removeImages", required = false) boolean removeImages,
            @RequestParam(value = "removeVideo", required = false) boolean removeVideo
    ) {
        try {
            // Get existing post
            SkillPost existingPost = skillPostService.findById(skillPostId);

            // Check if user is the author
            String googleId = principal.getAttributes().get("sub").toString();
            String userId = userService.findByGoogleId(googleId)
                    .getId()
                    .toString();

            if (!existingPost.getAuthorId().equals(userId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            // Create request object and set basic fields
            SkillPostRequest request = new SkillPostRequest();
            request.setSkillName(skillName);
            request.setTitle(title);
            request.setDescription(description);
            request.setVideoDurationSeconds(videoDurationSeconds);

            // Handle video update
            if (removeVideo && existingPost.getSkillPostVideoUrl() != null) {
                // Delete existing video and thumbnail
                storageService.deleteFile(existingPost.getSkillPostVideoUrl());
                if (existingPost.getSkillPostVideoThumbnailUrl() != null) {
                    storageService.deleteFile(existingPost.getSkillPostVideoThumbnailUrl());
                }
                request.setSkillPostVideoUrl(null);
                request.setSkillPostVideoThumbnailUrl(null);
            } else if (video != null && !video.isEmpty()) {
                // Delete existing video and thumbnail if there was one
                if (existingPost.getSkillPostVideoUrl() != null) {
                    storageService.deleteFile(existingPost.getSkillPostVideoUrl());
                }
                if (existingPost.getSkillPostVideoThumbnailUrl() != null) {
                    storageService.deleteFile(existingPost.getSkillPostVideoThumbnailUrl());
                }

                // Validate video duration
                if (videoDurationSeconds == null) {
                    throw new BadRequestException("Video duration must be provided for videos");
                }

                if (videoDurationSeconds > MAX_VIDEO_DURATION_SECONDS) {
                    throw new BadRequestException("Video duration cannot exceed " + MAX_VIDEO_DURATION_SECONDS + " seconds");
                }

                validateFileType(video, ALLOWED_VIDEO_TYPES);

                // Upload new video
                String videoUrl = storageService.uploadFile(video, video.getOriginalFilename());
                request.setSkillPostVideoUrl(videoUrl);

                // Generate and set new thumbnail
                String thumbnailUrl = thumbnailService.generateAndUploadThumbnail(video, videoDurationSeconds);
                request.setSkillPostVideoThumbnailUrl(thumbnailUrl);
            } else {
                // Keep existing video URL and thumbnail
                request.setSkillPostVideoUrl(existingPost.getSkillPostVideoUrl());
                request.setSkillPostVideoThumbnailUrl(existingPost.getSkillPostVideoThumbnailUrl());
            }

            // Handle image updates
            if (removeImages) {
                // Delete all existing images
                if (existingPost.getSkillPostImageUrls() != null) {
                    for (String imageUrl : existingPost.getSkillPostImageUrls()) {
                        storageService.deleteFile(imageUrl);
                    }
                }
                request.setSkillPostImageUrls(new ArrayList<>());
            } else if (images != null && images.length > 0) {
                // Delete existing images
                if (existingPost.getSkillPostImageUrls() != null) {
                    for (String imageUrl : existingPost.getSkillPostImageUrls()) {
                        storageService.deleteFile(imageUrl);
                    }
                }

                // Validate and upload new images
                if (images.length > MAX_IMAGES) {
                    throw new BadRequestException("Maximum " + MAX_IMAGES + " images allowed");
                }

                ArrayList<String> imageUrls = new ArrayList<>();

                for (MultipartFile image : images) {
                    validateFileType(image, ALLOWED_IMAGE_TYPES);
                    String imageUrl = storageService.uploadFile(image, image.getOriginalFilename());
                    imageUrls.add(imageUrl);
                }

                request.setSkillPostImageUrls(imageUrls);
            } else {
                // Keep existing image URLs
                request.setSkillPostImageUrls(existingPost.getSkillPostImageUrls());
            }

            // Update fields while preserving metadata
            skillPostMapper.updateEntityFromRequest(request, existingPost);

            // Save and return
            SkillPost updatedPost = skillPostService.update(existingPost);
            return ResponseEntity.ok(skillPostMapper.toDto(updatedPost));
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file", e);
        }
    }

    @DeleteMapping("/{skill-post-id}")
    public ResponseEntity<Void> deleteSkillPostById(
            @PathVariable("skill-post-id") String skillPostId,
            @AuthenticationPrincipal OAuth2IntrospectionAuthenticatedPrincipal principal
    ) {
        // Get existing post
        SkillPost existingPost = skillPostService.findById(skillPostId);

        // Check if user is the author
        String googleId = principal.getAttributes().get("sub").toString();
        String userId = userService.findByGoogleId(googleId)
                .getId()
                .toString();

        if (!existingPost.getAuthorId().equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // Delete associated files
        try {
            // Delete video if exists
            if (existingPost.getSkillPostVideoUrl() != null) {
                storageService.deleteFile(existingPost.getSkillPostVideoUrl());
            }

            // Delete video thumbnail if exists
            if (existingPost.getSkillPostVideoThumbnailUrl() != null) {
                storageService.deleteFile(existingPost.getSkillPostVideoThumbnailUrl());
            }

            // Delete images if exist
            if (existingPost.getSkillPostImageUrls() != null) {
                for (String imageUrl : existingPost.getSkillPostImageUrls()) {
                    storageService.deleteFile(imageUrl);
                }
            }
        } catch (Exception e) {
            // Log error but continue with post deletion
            System.err.println("Error deleting files for post " + skillPostId + ": " + e.getMessage());
        }

        skillPostService.delete(skillPostId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<List<SkillPostDto>> getAllSkillPosts() {
        List<SkillPost> allSkillPosts = skillPostService.findAll();
        List<SkillPostDto> dtos = allSkillPosts.stream()
                .map(skillPostMapper::toDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/skill/{skill-name}")
    public ResponseEntity<List<SkillPostDto>> getPostsBySkill(@PathVariable("skill-name") String skillName) {
        List<SkillPost> posts = skillPostService.findBySkillName(skillName);
        List<SkillPostDto> postDtos = posts.stream()
                .map(skillPostMapper::toDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(postDtos);
    }

    @GetMapping("/{skill-post-id}")
    public ResponseEntity<SkillPostDto> getSkillPostById(
            @PathVariable("skill-post-id") String skillPostId) {
        SkillPost skillPost = skillPostService.findById(skillPostId);
        return ResponseEntity.ok(skillPostMapper.toDto(skillPost));
    }

    // Social interaction endpoints

    @PostMapping("/{skill-post-id}/like")
    public ResponseEntity<SkillPostDto> likeSkillPost(
            @PathVariable("skill-post-id") String skillPostId,
            @AuthenticationPrincipal OAuth2IntrospectionAuthenticatedPrincipal principal
    ) {
        String googleId = principal.getAttributes().get("sub").toString();

        User user = userService.findByGoogleId(googleId);
        String userId = user.getId().toHexString();

        SkillPost updatedPost = skillPostService.addLike(skillPostId, userId);

        //Add Notification
        String notificationText = " liked your " + "'" + updatedPost.getTitle() + "'" + " skill post.";
        NotificationDto notificationDto = new NotificationDto(
                null,
                updatedPost.getAuthorId(),
                user.getName(),
                updatedPost.getSkillPostId().toHexString(),
                NotificationType.LIKE,
                notificationText,
                LocalDateTime.now(),
                false
        );

        notificationService.create(notificationMapper.toEntity(notificationDto));

        return ResponseEntity.ok(skillPostMapper.toDto(updatedPost));
    }

    @DeleteMapping("/{skill-post-id}/like")
    public ResponseEntity<SkillPostDto> unlikeSkillPost(
            @PathVariable("skill-post-id") String skillPostId,
            @AuthenticationPrincipal OAuth2IntrospectionAuthenticatedPrincipal principal
    ) {
        String googleId = principal.getAttributes().get("sub").toString();
        String userId = userService.findByGoogleId(googleId)
                .getId()
                .toString();

        SkillPost updatedPost = skillPostService.removeLike(skillPostId, userId);
        return ResponseEntity.ok(skillPostMapper.toDto(updatedPost));
    }

    @PostMapping("/{skill-post-id}/comments")
    public ResponseEntity<SkillPostDto> addComment(
            @PathVariable("skill-post-id") String skillPostId,
            @Valid @RequestBody CommentRequest commentRequest,
            @AuthenticationPrincipal OAuth2IntrospectionAuthenticatedPrincipal principal
    ) {
        SkillPost post = skillPostService.findById(skillPostId);

        // Set comment metadata
        String googleId = principal.getAttributes().get("sub").toString();
        var user = userService.findByGoogleId(googleId);

        Comment comment = commentMapper.toEntity(commentRequest);

        comment.setCommentId(new ObjectId());
        comment.setAuthorId(user.getId().toString());
        comment.setAuthorName(principal.getName());
        comment.setProfileImageUrl(user.getProfileImageUrl());

        // Add comment to post
        post.getComments().add(comment);
        SkillPost updatedPost = skillPostService.update(post);

        //Add Notification
        String notificationText = " commented on your " + "'" + updatedPost.getTitle() + "'" + " skill post.";
        NotificationDto notificationDto = new NotificationDto(
                null,
                updatedPost.getAuthorId(),
                user.getName(),
                updatedPost.getSkillPostId().toHexString(),
                NotificationType.COMMENT,
                notificationText,
                LocalDateTime.now(),
                false
        );

        notificationService.create(notificationMapper.toEntity(notificationDto));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(skillPostMapper.toDto(updatedPost));
    }

    @DeleteMapping("/{skill-post-id}/comments/{comment-id}")
    public ResponseEntity<SkillPostDto> deleteComment(
            @PathVariable("skill-post-id") String skillPostId,
            @PathVariable("comment-id") String commentId,
            @AuthenticationPrincipal OAuth2IntrospectionAuthenticatedPrincipal principal
    ) {
        SkillPost post = skillPostService.findById(skillPostId);

        // Get user ID
        String googleId = principal.getAttributes().get("sub").toString();
        String userId = userService.findByGoogleId(googleId)
                .getId()
                .toString();
        ObjectId commentObjectId = new ObjectId(commentId);

        // Find comment
        Comment commentToDelete = post.getComments().stream()
                .filter(c -> c.getCommentId().equals(commentObjectId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Comment not found"));

        // Check if user is either comment author or post owner
        if (!commentToDelete.getAuthorId().equals(userId) && !post.getAuthorId().equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // Remove comment
        post.getComments().removeIf(c -> c.getCommentId().equals(commentObjectId));
        SkillPost updatedPost = skillPostService.update(post);

        return ResponseEntity.ok(skillPostMapper.toDto(updatedPost));
    }

    @PutMapping("/{skill-post-id}/comments/{comment-id}")
    public ResponseEntity<SkillPostDto> updateComment(
            @PathVariable("skill-post-id") String skillPostId,
            @PathVariable("comment-id") String commentId,
            @Valid @RequestBody CommentRequest updatedCommentRequest,
            @AuthenticationPrincipal OAuth2IntrospectionAuthenticatedPrincipal principal
    ) {
        SkillPost post = skillPostService.findById(skillPostId);

        // Get user ID
        String googleId = principal.getAttributes().get("sub").toString();
        String userId = userService.findByGoogleId(googleId)
                .getId()
                .toString();

        ObjectId commentObjectId = new ObjectId(commentId);

        // Find comment
        Comment existingComment = post.getComments().stream()
                .filter(c -> c.getCommentId().equals(commentObjectId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Comment not found"));

        // Check if user is comment author
        if (!existingComment.getAuthorId().equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Comment updatedComment = commentMapper.toEntity(updatedCommentRequest);

        // Update only the content, preserve metadata
        existingComment.setContent(updatedComment.getContent());

        SkillPost updatedPost = skillPostService.update(post);

        return ResponseEntity.ok(skillPostMapper.toDto(updatedPost));
    }

    @GetMapping("/user/{user-id}")
    public ResponseEntity<List<SkillPostDto>> getPostsByUser(
            @PathVariable("user-id") String userId) {
        List<SkillPost> posts = skillPostService.findByUserId(userId);
        List<SkillPostDto> postDtos = posts.stream()
                .map(skillPostMapper::toDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(postDtos);
    }

    @GetMapping("/me")
    public ResponseEntity<List<SkillPostDto>> getCurrentUserPosts(
            @AuthenticationPrincipal OAuth2IntrospectionAuthenticatedPrincipal principal) {

        String googleId = principal.getAttributes().get("sub").toString();
        User user = userService.findByGoogleId(googleId);

        List<SkillPost> posts = skillPostService.findByUserId(user.getId().toHexString());
        List<SkillPostDto> postDtos = posts.stream()
                .map(skillPostMapper::toDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(postDtos);
    }

    @GetMapping("/feed")
    public ResponseEntity<List<SkillPostDto>> getFeed(
            @AuthenticationPrincipal OAuth2IntrospectionAuthenticatedPrincipal principal) {

        String googleId = principal.getAttributes().get("sub").toString();
        User user = userService.findByGoogleId(googleId);

        // Get IDs of users that the current user follows
        List<String> followingIds = user.getFollowing().stream()
                .map(ObjectId::toHexString)
                .collect(Collectors.toList());

        List<SkillPost> posts = skillPostService.getFeedForUser(
                user.getId().toHexString(), followingIds);

        List<SkillPostDto> postDtos = posts.stream()
                .map(skillPostMapper::toDto)
                .collect(Collectors.toList());

        return ResponseEntity.ok(postDtos);
    }

    @GetMapping("/liked")
    public ResponseEntity<List<SkillPostDto>> getLikedPosts(
            @AuthenticationPrincipal OAuth2IntrospectionAuthenticatedPrincipal principal) {

        String googleId = principal.getAttributes().get("sub").toString();
        User user = userService.findByGoogleId(googleId);

        List<SkillPost> posts = skillPostService.findLikedByUser(user.getId().toHexString());
        List<SkillPostDto> postDtos = posts.stream()
                .map(skillPostMapper::toDto)
                .collect(Collectors.toList());

        return ResponseEntity.ok(postDtos);
    }

    @GetMapping("/suggested")
    public ResponseEntity<List<SkillPostDto>> getSuggestedPosts(
            @AuthenticationPrincipal OAuth2IntrospectionAuthenticatedPrincipal principal,
            @RequestParam(value = "limit", defaultValue = "6") int limit) {

        String googleId = principal.getAttributes().get("sub").toString();
        User user = userService.findByGoogleId(googleId);

        // Get all posts
        List<SkillPost> allPosts = skillPostService.findAll();

        // Get following IDs as strings
        List<String> followingIds = user.getFollowing() != null ?
                user.getFollowing().stream()
                        .map(ObjectId::toHexString)
                        .toList()
                : new ArrayList<>();

        // Get the current user's ID
        String userId = user.getId().toHexString();

        List<SkillPostDto> suggestedPosts = allPosts.stream()
                // Filter out posts from users the current user follows and their own posts
                .filter(post -> !followingIds.contains(post.getAuthorId())
                        && !post.getAuthorId().equals(userId))
                // Sort by suggestion score
                .sorted((p1, p2) -> {
                    int p1Score = calculateSuggestionScore(p1, user);
                    int p2Score = calculateSuggestionScore(p2, user);
                    return Integer.compare(p2Score, p1Score);
                })
                .limit(limit)
                .map(skillPostMapper::toDto)
                .collect(Collectors.toList());

        return ResponseEntity.ok(suggestedPosts);
    }

    @GetMapping("/public/suggested")
    public ResponseEntity<List<SkillPostDto>> getPublicSuggestedPosts(
            @RequestParam(value = "limit", defaultValue = "6") int limit) {

        List<SkillPost> allPosts = skillPostService.findAll();

        List<SkillPostDto> suggestedPosts = allPosts.stream()
                .sorted((p1, p2) -> {
                    int p1Score = calculateGeneralSuggestionScore(p1);
                    int p2Score = calculateGeneralSuggestionScore(p2);
                    return Integer.compare(p2Score, p1Score);
                })
                .limit(limit)
                .map(skillPostMapper::toDto)
                .collect(Collectors.toList());

        return ResponseEntity.ok(suggestedPosts);
    }

    // Helper method to calculate suggestion score for authenticated users
    private int calculateSuggestionScore(SkillPost post, User user) {
        int score = 0;

        // More likes = higher score
        score += post.getNoOfLikes() * 2;

        // Recent posts get bonus (assuming SkillPost has createdAt field)
        if (post.getCreatedAt() != null) {
            long daysSinceCreation = Duration.between(post.getCreatedAt(), LocalDateTime.now()).toDays();
            if (daysSinceCreation <= 7) {
                score += 10 - (int) daysSinceCreation; // Newer posts get higher bonus
            }
        }

        // Posts with good comments ratio
        if (post.getComments() != null && !post.getComments().isEmpty()) {
            score += post.getComments().size() * 3;
        }

        // Add bonus if user has recently joined (might want to discover skills)
        if (user.getCreatedAt() != null) {
            long daysSinceJoined = Duration.between(user.getCreatedAt(), LocalDateTime.now()).toDays();
            if (daysSinceJoined <= 14) {
                score += 5; // New users get more diverse suggestions
            }
        }

        return score;
    }

    // Helper method to calculate suggestion score for unauthenticated users
    private int calculateGeneralSuggestionScore(SkillPost post) {
        int score = 0;

        // More likes = higher score
        score += post.getNoOfLikes() * 3;

        // Recent posts get bonus
        if (post.getCreatedAt() != null) {
            long daysSinceCreation = Duration.between(post.getCreatedAt(), LocalDateTime.now()).toDays();
            if (daysSinceCreation <= 7) {
                score += 15 - ((int) daysSinceCreation * 2); // Newer posts get higher bonus
            }
        }

        // Posts with good engagement get bonus
        if (post.getComments() != null && !post.getComments().isEmpty()) {
            score += post.getComments().size() * 5;
        }

        // Give bonus to posts with media content
        if ((post.getSkillPostImageUrls() != null && !post.getSkillPostImageUrls().isEmpty())
                || post.getSkillPostVideoUrl() != null) {
            score += 10;
        }

        return score;
    }

    @GetMapping("/public/featured")
    public ResponseEntity<List<SkillPostDto>> getFeaturedSkillPosts() {
        List<SkillPost> allSkillPosts = skillPostService.findAll();

        // Get only 6 most engaging posts for unauthenticated users
        List<SkillPostDto> featuredPosts = allSkillPosts.stream()
                .sorted((p1, p2) -> {
                    // Combine different metrics for featured posts
                    int p1Score = calculateFeaturedScore(p1);
                    int p2Score = calculateFeaturedScore(p2);
                    return Integer.compare(p2Score, p1Score);
                })
                .limit(6)
                .map(skillPostMapper::toDto)
                .collect(Collectors.toList());

        return ResponseEntity.ok(featuredPosts);
    }

    private int calculateFeaturedScore(SkillPost post) {
        int score = 0;

        // Likes have high weight for featured posts
        score += post.getNoOfLikes() * 5;

        // Comments indicate engaging content
        score += post.getComments() != null ? post.getComments().size() * 7 : 0;

        // Prefer recent posts
        if (post.getCreatedAt() != null) {
            long hoursSinceCreation = Duration.between(post.getCreatedAt(), LocalDateTime.now()).toHours();
            if (hoursSinceCreation <= 48) {
                score += 20; // Recent posts get significant boost
            } else if (hoursSinceCreation <= 168) { // 1 week
                score += 10;
            }
        }

        // Bonus for content with media
        if (post.getSkillPostVideoUrl() != null) {
            score += 15; // Videos get extra priority
        }
        if (post.getSkillPostImageUrls() != null && !post.getSkillPostImageUrls().isEmpty()) {
            score += 8;
        }

        return score;
    }

    @GetMapping("/user/{user-id}/total-likes")
    public ResponseEntity<Integer> getTotalLikesForUser(@PathVariable("user-id") String userId) {
        // Validate that the user exists
        User user = userService.findById(userId);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        int totalLikes = skillPostService.getTotalLikesForUser(userId);
        return ResponseEntity.ok(totalLikes);
    }

    @GetMapping("/me/total-likes")
    public ResponseEntity<Integer> getCurrentUserTotalLikes(
            @AuthenticationPrincipal OAuth2IntrospectionAuthenticatedPrincipal principal) {

        // Get and validate the current user
        String googleId = principal.getAttributes().get("sub").toString();
        User user;
        try {
            user = userService.findByGoogleId(googleId);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        int totalLikes = skillPostService.getTotalLikesForUser(user.getId().toHexString());
        return ResponseEntity.ok(totalLikes);
    }
}