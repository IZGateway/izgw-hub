package gov.cdc.izgateway.hub.service;

import net.logstash.logback.util.StringUtils;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import gov.cdc.izgateway.dynamodb.model.MessageHeader;
import gov.cdc.izgateway.hub.repository.IMessageHeaderRepository;
import gov.cdc.izgateway.hub.repository.RepositoryFactory;
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

    private final IMessageHeaderRepository<MessageHeader> messageHeaderRepository;
    private Map<String, IMessageHeader> cache = Collections.emptyMap();

    @Value("${data.cache.timeToLive:300}")
    private int refreshPeriod;
    
    public MessageHeaderService(RepositoryFactory factory) {
        this.messageHeaderRepository = factory.messageHeaderRepository();
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
        List<? extends IMessageHeader> list = Collections.unmodifiableList(messageHeaderRepository.findAll());
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
        return new ArrayList<>(cache.values());
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
	public MessageHeader saveAndFlush(IMessageHeader h) {
		MessageHeader h2;
		if (h instanceof MessageHeader h3) {
			h2 = h3;
		} else {
			h2 = new MessageHeader(h);
		}
		h2 = messageHeaderRepository.store(h2);
		// Update cache
		cache.put(h2.getMsh(), h2);
		return h2;
	}

	@Override
	public void delete(String id) {
		cache.remove(id);
		messageHeaderRepository.deleteById(id);
	}

}
