package io.choerodon.notify.api.service.impl;

import freemarker.template.TemplateException;
import io.choerodon.core.exception.CommonException;
import io.choerodon.core.exception.FeignException;
import io.choerodon.notify.api.service.WebSocketSendService;
import io.choerodon.notify.domain.SendSetting;
import io.choerodon.notify.domain.SiteMsgRecord;
import io.choerodon.notify.domain.Template;
import io.choerodon.notify.infra.mapper.SendSettingMapper;
import io.choerodon.notify.infra.mapper.SiteMsgRecordMapper;
import io.choerodon.notify.infra.mapper.TemplateMapper;
import io.choerodon.notify.websocket.send.MessageSender;
import io.choerodon.notify.websocket.send.WebSocketSendPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

@Service("pmWsSendService")
public class WebSocketWsSendServiceImpl implements WebSocketSendService {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketWsSendServiceImpl.class);
    public static final String MSG_TYPE_PM = "site-msg";

    private final TemplateRender templateRender;

    private final TemplateMapper templateMapper;

    private final SiteMsgRecordMapper siteMsgRecordMapper;

    private final MessageSender messageSender;

    private final SendSettingMapper sendSettingMapper;

    public WebSocketWsSendServiceImpl(TemplateRender templateRender,
                                      TemplateMapper templateMapper,
                                      MessageSender messageSender,
                                      SiteMsgRecordMapper siteMsgRecordMapper,
                                      SendSettingMapper sendSettingMapper) {
        this.templateRender = templateRender;
        this.templateMapper = templateMapper;
        this.messageSender = messageSender;
        this.siteMsgRecordMapper = siteMsgRecordMapper;
        this.sendSettingMapper = sendSettingMapper;
    }

    @Override
    public void send(String code, Map<String, Object> params, Set<Long> ids) {
        SendSetting sendSetting = sendSettingMapper.selectOne(new SendSetting(code));
        if (code == null || sendSetting == null) {
            logger.info("no sendsetting,cann`t send station letter.");
            return;
        }
        if (sendSetting.getPmTemplateId() == null) {
            logger.info("sendsetting no opposite station letter template,cann`t send station letter.");
            return;
        }
        Template template = templateMapper.selectByPrimaryKey(sendSetting.getPmTemplateId());
        if (template == null) {
            throw new CommonException("error.pmTemplate.notExist");
        }
        if (template.getPmContent() == null) {
            throw new CommonException("error.pmTemplate.contentNull");
        }
        try {
            for (Long id : ids) {
                String pm = templateRender.renderPmTemplate(template, params);
                SiteMsgRecord record = new SiteMsgRecord(id, template.getPmTitle(), pm);
                if (siteMsgRecordMapper.insert(record) != 1) {
                    throw new FeignException("error.pmSendService.send.siteMsgRecordInsertError");
                }
                String key = "choerodon:msg:site-msg:" + id;
                messageSender.sendByKey(key, new WebSocketSendPayload<>(MSG_TYPE_PM, key, siteMsgRecordMapper.selectCountOfUnRead(id)));
            }
        } catch (IOException | TemplateException e) {
            throw new CommonException("error.templateRender.renderError", e);
        }
    }
}
