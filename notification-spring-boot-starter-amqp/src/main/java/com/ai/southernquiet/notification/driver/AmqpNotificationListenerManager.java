package com.ai.southernquiet.notification.driver;

import com.ai.southernquiet.amqp.rabbit.AmqpAutoConfiguration;
import com.ai.southernquiet.amqp.rabbit.AmqpMessageRecover;
import com.ai.southernquiet.amqp.rabbit.DirectRabbitListenerContainerFactoryConfigurer;
import com.ai.southernquiet.notification.NotificationListener;
import com.ai.southernquiet.util.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.DirectRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistrar;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.*;

import static com.ai.southernquiet.Constants.AMQP_DLK;
import static com.ai.southernquiet.Constants.AMQP_DLX;

public class AmqpNotificationListenerManager extends AbstractListenerManager {
    private final static Logger log = LoggerFactory.getLogger(AmqpNotificationListenerManager.class);

    private MessageConverter messageConverter;
    private ConnectionFactory connectionFactory;

    private List<Tuple<RabbitListenerEndpoint, NotificationListener, String>> listenerEndpoints = new ArrayList<>();
    private RabbitAdmin rabbitAdmin;
    private AmqpNotificationPublisher publisher;
    private AmqpAutoConfiguration.Properties amqpProperties;

    private RabbitProperties rabbitProperties;
    private ApplicationContext applicationContext;

    private PlatformTransactionManager transactionManager;

    public AmqpNotificationListenerManager(ConnectionFactory connectionFactory,
                                           RabbitAdmin rabbitAdmin,
                                           AmqpNotificationPublisher publisher,
                                           AmqpAutoConfiguration.Properties amqpProperties,
                                           RabbitProperties rabbitProperties,
                                           PlatformTransactionManager transactionManager,
                                           ApplicationContext applicationContext
    ) {
        this.connectionFactory = connectionFactory;
        this.rabbitAdmin = rabbitAdmin;
        this.publisher = publisher;
        this.amqpProperties = amqpProperties;
        this.rabbitProperties = rabbitProperties;
        this.transactionManager = transactionManager;
        this.applicationContext = applicationContext;

        this.messageConverter = publisher.getMessageConverter();
    }

    public void registerListeners(RabbitListenerEndpointRegistrar registrar) {
        initListener(applicationContext);

        listenerEndpoints.forEach(tuple -> {
            RabbitListenerEndpoint endpoint = tuple.getFirst();
            NotificationListener listenerAnnotation = tuple.getSecond();
            String listenerDefaultName = tuple.getThird();

            DirectRabbitListenerContainerFactoryConfigurer containerFactoryConfigurer = new DirectRabbitListenerContainerFactoryConfigurer();
            containerFactoryConfigurer.setRabbitProperties(rabbitProperties);
            containerFactoryConfigurer.setMessageRecoverer(new AmqpMessageRecover(
                publisher.getRabbitTemplate(),
                getDeadExchange(listenerAnnotation, listenerDefaultName),
                getDeadRouting(listenerAnnotation, listenerDefaultName),
                amqpProperties
            ));

            DirectRabbitListenerContainerFactory factory = new DirectRabbitListenerContainerFactory();
            if (listenerAnnotation.isTransactionEnabled()) {
                factory.setTransactionManager(transactionManager);
            }
            factory.setMessageConverter(publisher.getMessageConverter());
            containerFactoryConfigurer.configure(factory, connectionFactory);

            registrar.registerEndpoint(endpoint, factory);
        });
    }

    @Override
    protected void initListener(NotificationListener listener, Object bean, Method method) {
        SimpleRabbitListenerEndpoint endpoint = new SimpleRabbitListenerEndpoint();

        String listenerDefaultName = method.getName();

        endpoint.setId(UUID.randomUUID().toString());
        endpoint.setQueueNames(getListenerRouting(listener, listenerDefaultName));
        endpoint.setAdmin(rabbitAdmin);

        declareExchangeAndQueue(listener, listenerDefaultName);

        endpoint.setMessageListener(message -> {
            Object notification = messageConverter.fromMessage(message);
            Object[] parameters = Arrays.stream(method.getParameters())
                .map(parameter -> {
                    Class<?> cls = parameter.getType();

                    if (cls.isInstance(notification)) {
                        return notification;
                    }
                    else if (cls.isInstance(listener)) {
                        return listener;
                    }

                    throw new UnsupportedOperationException("不支持在通知监听器中使用此类型的参数：" + parameter.toString());
                })
                .toArray();

            if (log.isDebugEnabled()) {
                log.debug(
                    "使用监听器({}#{})收到通知: {}",
                    listener.notification().getSimpleName(),
                    StringUtils.isEmpty(listener.name()) ? listenerDefaultName : listener.name(),
                    notification
                );
            }

            try {
                method.invoke(bean, parameters);
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        listenerEndpoints.add(new Tuple<>(endpoint, listener, listenerDefaultName));
    }

    @SuppressWarnings("unchecked")
    private String getDeadSource(NotificationListener listener, String listenerDefaultName) {
        return suffix("DEAD." + publisher.getNotificationSource(listener.notification()), listener, listenerDefaultName);
    }

    private String getDeadExchange(NotificationListener listener, String listenerDefaultName) {
        return publisher.getExchange(getDeadSource(listener, listenerDefaultName));
    }

    private String getDeadRouting(NotificationListener listener, String listenerDefaultName) {
        return publisher.getRouting(getDeadSource(listener, listenerDefaultName));
    }

    @SuppressWarnings("unchecked")
    private String getListenerRouting(NotificationListener listener, String listenerDefaultName) {
        return suffix(publisher.getRouting(publisher.getNotificationSource(listener.notification())), listener, listenerDefaultName);
    }

    private String suffix(String routing, NotificationListener listener, String listenerDefaultName) {
        String listenerName = listener.name();
        if (StringUtils.isEmpty(listenerName)) {
            listenerName = listenerDefaultName;
        }

        Assert.hasText(listenerName, "监听器的名称不能为空");

        return routing + "#" + listenerName;
    }

    @SuppressWarnings("unchecked")
    private void declareExchangeAndQueue(NotificationListener listener, String listenerDefaultName) {
        String routing = getListenerRouting(listener, listenerDefaultName);

        Exchange exchange = publisher.declareExchange(listener.notification());
        Queue queue = new Queue(routing);

        rabbitAdmin.declareExchange(exchange);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(routing).noargs());


        Map<String, Object> deadQueueArgs = new HashMap<>();
        deadQueueArgs.put(AMQP_DLX, exchange.getName());
        deadQueueArgs.put(AMQP_DLK, queue.getName());

        Exchange deadExchange = new DirectExchange(getDeadExchange(listener, listenerDefaultName));
        Queue deadRouting = new Queue(getDeadRouting(listener, listenerDefaultName), true, false, false, deadQueueArgs);

        rabbitAdmin.declareExchange(deadExchange);
        rabbitAdmin.declareQueue(deadRouting);
        rabbitAdmin.declareBinding(BindingBuilder.bind(deadRouting).to(deadExchange).with(deadRouting.getName()).noargs());
    }
}
