package com.lyw.friend.service;

import com.lyw.friend.domain.question.Question;
import com.lyw.friend.elasticsearch.QuestionRepository;
import com.lyw.friend.mapper.question.QuestionMapper;
import com.lyw.friend.service.question.impl.QuestionServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuestionReadBoundaryTest {
    @Test void detailUsesPrimaryDatabaseEvenWhenSearchIsUnavailable() {
        var mapper = mock(QuestionMapper.class); var repository = mock(QuestionRepository.class);
        var service = new QuestionServiceImpl();
        ReflectionTestUtils.setField(service, "questionMapper", mapper); ReflectionTestUtils.setField(service, "questionRepository", repository);
        Question current = new Question(); current.setQuestionId(1L); current.setTitle("主库最新标题"); current.setContent("最新题干");
        when(mapper.selectById(1L)).thenReturn(current);
        assertEquals("主库最新标题", service.detail(1L).getTitle());
        assertNull(service.detail(2L)); verify(mapper).selectById(1L); verify(mapper).selectById(2L);
        verifyNoInteractions(repository);
    }
}
