package com.example.skillv.skillpost;

import com.example.skillv.comment.Comment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Document("skill_posts")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SkillPost {
    @Id private ObjectId skillPostId;
    private String authorId;
    private String authorName;
    private String authorProfileImageUrl;
    private ArrayList<String> skillPostImageUrls;
    private String skillPostVideoUrl;
    private String skillPostVideoThumbnailUrl;
    private Integer videoDurationSeconds;
    private String skillName;
    private String title;
    private String description;
    private int noOfLikes;
    private Set<String> likedUserIds;
    private List<Comment> comments;
    private LocalDateTime createdAt;
}
