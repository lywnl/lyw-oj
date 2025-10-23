package com.lyw.system.test.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lyw.system.test.domain.TestDomain;
import com.lyw.system.test.mapper.TestMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TestServiceImpl implements ITestService {

    @Autowired
    private TestMapper testMapper;

    @Autowired
    @Override
    public List<?> list() {
        List<TestDomain> testDomains = testMapper.selectList(new LambdaQueryWrapper<>());
        return testDomains;
    }

    @Override
    public String add() {
        TestDomain testDomain = new TestDomain();
        testDomain.setTitle("测试");
        testDomain.setContent("测试雪花算法生成主键id");
        testMapper.insert(testDomain);
        return "添加成功";
    }

}
