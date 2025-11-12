#
创建管理员账户
create table tb_sys_user
(
    user_id      bigint unsigned not null comment '用户id（主键）',
    user_account varchar(20) not null comment '账号',
    nick_name    varchar(20) comment '昵称',
    password     char(60)    not null comment '密码',
    create_by    bigint unsigned not null comment '创建人',
    create_time  datetime    not null comment '创建时间',
    update_by    bigint unsigned comment '更新人',
    update_time  datetime comment '跟新时间',
    primary key (`user_id`),
    unique key `idx_user_account` (`user_account`)
);

#
题库管理
create table tb_question
(
    question_id   bigint unsigned not null comment '题目id',
    title         varchar(50)   not null comment '题目标题',
    difficulty    tinyint       not null comment '题目难度1:简单  2：中等 3：困难',
    time_limit    int           not null comment '时间限制',
    space_limit   int           not null comment '空间限制',
    content       varchar(1000) not null comment '题目内容',
    question_case varchar(1000) comment '题目用例',
    default_code  varchar(500)  not null comment '默认代码块',
    main_fuc      varchar(500)  not null comment 'main函数',
    create_by     bigint unsigned not null comment '创建人',
    create_time   datetime      not null comment '创建时间',
    update_by     bigint unsigned comment '更新人',
    update_time   datetime comment '更新时间',
    primary key (`question_id`)
);

--竞赛管理
-- B端：列表、新增、编辑、删除、发布、撤销发布

-- C端：列表（未开始、历史） 、报名参赛、我的比赛、参加竞赛（竞赛倒计时、竞赛内题目切换、完成竞赛） 、竞赛练习、查看排名、我的消息
create table tb_exam
(
    exam_id     bigint unsigned not null comment '竞赛id（主键）',
    title       varchar(50) not null comment '竞赛标题',
    start_time  datetime    not null comment '竞赛开始时间',
    end_time    datetime    not null comment '竞赛结束时间',
    status      tinyint     not null default '0' comment '是否发布 0：未发布  1：已发布',
    create_by   bigint unsigned not null comment '创建人',
    create_time datetime    not null comment '创建时间',
    update_by   bigint unsigned comment '更新人',
    update_time datetime comment '更新时间',
    primary key (exam_id)
)

-- 题目和竞赛之间是多对一的关系
-- 题目和竞赛的关系表
create table tb_exam_question
(
    exam_question_id bigint unsigned not null comment '竞赛题目关系id（主键）',
    question_id      bigint unsigned not null comment '题目id（主键）',
    exam_id          bigint unsigned not null comment '竞赛id（主键）',
    question_order   int      not null comment '题目顺序',
    create_by        bigint unsigned not null comment '创建人',
    create_time      datetime not null comment '创建时间',
    update_by        bigint unsigned comment '更新人',
    update_time      datetime comment '更新时间',
    primary key (exam_question_id)
)

-- B端对C端的用户管理

-- B端：列表、修改用户状态
-- C端：登录、注册、退出登录、个人中心
create table tb_user(
    user_id  bigint unsigned NOT NULL COMMENT '用户id（主键）',
    nick_name varchar(20) comment '用户昵称',
    head_image varchar(100) comment '用户头像',
    sex tinyint comment '用户状态1: 男  2：女',
    phone char(11) not null comment '手机号',
    code  char(6) comment '验证码',
    email varchar(20) comment '邮箱',
    wechat varchar(20) comment '微信号',
    school_name  varchar(20) comment '学校',
    major_name  varchar(20) comment '专业',
    introduce varchar(100) comment '个人介绍',
    status tinyint not null comment '用户状态0: 拉黑  1：正常',
    create_by    bigint unsigned not null  comment '创建人',
    create_time  datetime not null comment '创建时间',
    update_by    bigint unsigned  comment '更新人',
    update_time  datetime comment '更新时间',
    primary key(`user_id`)
)

-- 用户竞赛报名 竞赛用户表
create table tb_user_exam(
    user_exam_id bigint unsigned NOT NULL COMMENT '用户竞赛关系id',
    user_id bigint unsigned NOT NULL COMMENT '用户id',
    exam_id bigint unsigned NOT NULL COMMENT '竞赛id',
    score int unsigned COMMENT '得分',
    exam_rank  int unsigned COMMENT '排名',
    create_by    bigint unsigned not null  comment '创建人',
    create_time  datetime not null comment '创建时间',
    update_by    bigint unsigned  comment '更新人',
    update_time  datetime comment '更新时间',
    primary key(user_exam_id)
)



