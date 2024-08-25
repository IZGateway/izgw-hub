package gov.cdc.izgateway.dynamodb.service;

import net.logstash.logback.util.StringUtils;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import gov.cdc.izgateway.db.model.MessageHeader;
import gov.cdc.izgateway.db.repository.MessageHeaderInfoRepository;
import gov.cdc.izgateway.model.IMessageHeader;
import gov.cdc.izgateway.service.IMessageHeaderService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


@Service
@Slf4j
public class MessageHeaderService implements InitializingBean, IMessageHeaderService {

    private final MessageHeaderInfoRepository messageHeaderInfoRepository;
    private Map<String, IMessageHeader> cache = Collections.emptyMap();
    private List<IMessageHeader> list = Collections.emptyList();

    @Value("${data.cache.timeToLive:300}")
    private int refreshPeriod;
    
    public MessageHeaderService(MessageHeaderInfoRepository messageHeaderInfoRepository) {
        this.messageHeaderInfoRepository = messageHeaderInfoRepository;
    }
    
    /**
     * Configure service to update itself periodically after initialization.
     */
    public void afterPropertiesSet() { 
        log.debug("Refresh Scheduled for MessageHeader");
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(this::refresh, 0, refreshPeriod, TimeUnit.SECONDS);
    }
    
    @Override
	public void refresh() {
        list = Collections.unmodifiableList(messageHeaderInfoRepository.findAll());
        Map<String, IMessageHeader> map = new LinkedHashMap<>();
        // Initialize new cache
        for (IMessageHeader msh: list) {
            if (msh.getMsh() != null) {
                map.put(msh.getMsh(), msh);
            }
        }
        cache = map;
        log.debug("MessageHeaders Refreshed");
    }

    @Override
	public IMessageHeader findByMsgId(String msgId) {
        if (cache.isEmpty()) {
            refresh();
        }
        return cache.get(msgId);
    }
    
    @Override
	public List<IMessageHeader> getMessageHeaders(List<String> mshList) {
        if (cache.isEmpty()) {
            refresh();
        }
        List<IMessageHeader> result = new ArrayList<>();
        for (String mshId : mshList) {
        	if (StringUtils.isEmpty(mshId)) {
        		continue;
        	}
            IMessageHeader msh = cache.get(mshId);
            if (msh != null) {
                result.add(msh);
            }
        }
        return result;
    }
    
    @Override
	public List<IMessageHeader> getAllMessageHeaders() {
        if (cache.isEmpty()) {
            refresh();
        }
        return list;
    }
    
    @Override
	public String getSourceType(String ... idList) {
        if (cache.isEmpty()) {
            refresh();
        }

        for (String mshId : idList) {
        	if (mshId == null) {
        		continue;
        	}
            IMessageHeader msh = cache.get(mshId);
            if (msh != null) {
                return msh.getSourceType();
            }
        }
        return null;
    }

	@Override
	public IMessageHeader saveAndFlush(IMessageHeader h) {
		if (h instanceof MessageHeader h1) {
			h = messageHeaderInfoRepository.saveAndFlush(h1);
		} else {
			MessageHeader h1 = new MessageHeader(h);
			h = messageHeaderInfoRepository.saveAndFlush(h1);
		}
		// Update cache
		if (cache.get(h.getMsh()) != null) {
			cache.put(h.getMsh(), h);
		}
		return h;
	}

}
