package org.hibernate.rx;

import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.hibernate.boot.registry.BootstrapServiceRegistry;
import org.hibernate.boot.registry.BootstrapServiceRegistryBuilder;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.registry.internal.StandardServiceRegistryImpl;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Environment;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.internal.util.config.ConfigurationHelper;
import org.hibernate.rx.util.impl.RxUtil;
import org.hibernate.service.ServiceRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.persistence.*;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

@RunWith(VertxUnitRunner.class)
public class TableGeneratorTest {

	private static void test(TestContext context, CompletionStage<?> cs) {
		// this will be added to TestContext in the next vert.x release
		Async async = context.async();
		cs.whenComplete( (res, err) -> {
			if ( err != null ) {
				context.fail( err );
			}
			else {
				async.complete();
			}
		} );
	}

	@Rule
	public Timeout rule = Timeout.seconds( 3600 );

	RxHibernateSession session = null;
	SessionFactoryImplementor sessionFactory = null;

	protected Configuration constructConfiguration() {
		Configuration configuration = new Configuration();
		configuration.setProperty( Environment.HBM2DDL_AUTO, "create-drop" );
		configuration.setProperty( AvailableSettings.SHOW_SQL, "true" );
		configuration.setProperty( AvailableSettings.URL, "jdbc:postgresql://localhost:5432/hibernate-rx?user=hibernate-rx&password=hibernate-rx" );

		configuration.addAnnotatedClass( TableId.class );
		return configuration;
	}

	protected BootstrapServiceRegistry buildBootstrapServiceRegistry() {
		final BootstrapServiceRegistryBuilder builder = new BootstrapServiceRegistryBuilder();
		builder.applyClassLoader( getClass().getClassLoader() );
		return builder.build();
	}

	protected StandardServiceRegistryImpl buildServiceRegistry(
			BootstrapServiceRegistry bootRegistry,
			Configuration configuration) {
		Properties properties = new Properties();
		properties.putAll( configuration.getProperties() );
		ConfigurationHelper.resolvePlaceHolders( properties );

		StandardServiceRegistryBuilder cfgRegistryBuilder = configuration.getStandardServiceRegistryBuilder();
		StandardServiceRegistryBuilder registryBuilder = new StandardServiceRegistryBuilder( bootRegistry, cfgRegistryBuilder.getAggregatedCfgXml() )
				.applySettings( properties );

		return (StandardServiceRegistryImpl) registryBuilder.build();
	}

	@Before
	public void init() {
		// for now, build the configuration to get all the property settings
		Configuration configuration = constructConfiguration();
		BootstrapServiceRegistry bootRegistry = buildBootstrapServiceRegistry();
		ServiceRegistry serviceRegistry = buildServiceRegistry( bootRegistry, configuration );
		// this is done here because Configuration does not currently support 4.0 xsd
		sessionFactory = (SessionFactoryImplementor) configuration.buildSessionFactory( serviceRegistry );
		session = sessionFactory.unwrap( RxHibernateSessionFactory.class ).openRxSession();
	}

	@After
	// The current test should have already called context.async().complete();
	public void tearDown(TestContext context) {
		sessionFactory.close();
	}
//
//	private RxConnection connection() {
//		RxConnectionPoolProvider poolProvider = sessionFactory.getServiceRegistry()
//				.getService( RxConnectionPoolProvider.class );
//		return poolProvider.getConnection();
//	}

	@Test
	public void testTableGenerator(TestContext context) {

		TableId b = new TableId();
		b.string = "Hello World";

		test( context,
				session()
				.thenCompose(s -> s.persist(b))
				.thenCompose(s -> s.flush())
				.thenApply(newSession())
				.thenCompose( s2 ->
					s2.find( TableId.class, b.getId() )
						.thenAccept( bt -> {
							context.assertTrue( bt.isPresent() );
							TableId bb = bt.get();
							context.assertEquals( bb.id, 6 );
							context.assertEquals( bb.string, b.string );
							context.assertEquals( bb.version, 0 );

							bb.string = "Goodbye";
						})
						.thenCompose(vv -> s2.flush())
						.thenCompose(vv -> s2.find( TableId.class, b.getId() ))
						.thenAccept( bt -> {
							context.assertEquals( bt.get().version, 1 );
						}))
				.thenApply(newSession())
				.thenCompose( s3 -> s3.find( TableId.class, b.getId() ) )
				.thenAccept( bt -> {
					TableId bb = bt.get();
					context.assertEquals(bb.version, 1);
					context.assertEquals( bb.string, "Goodbye");
				})
		);
	}

	private Function<Object, RxSession> newSession() {
		return v -> {
			session.close();
			session = sessionFactory.unwrap( RxHibernateSessionFactory.class ).openRxSession();
			return session.reactive();
		};
	}

	private CompletionStage<RxSession> session() {
		return RxUtil.completedFuture(session.reactive());
	}

	enum Cover { hard, soft }

	@Entity
	@TableGenerator(name = "tab",
			valueColumnName = "nextid",
			table = "test_id_tab",
			initialValue = 5)
	public static class TableId {
		@Id @GeneratedValue(generator = "tab")
		Integer id;
		@Version Integer version;
		String string;

		public TableId() {
		}

		public TableId(Integer id, String string) {
			this.id = id;
			this.string = string;
		}

		public Integer getId() {
			return id;
		}

		public void setId(Integer id) {
			this.id = id;
		}

		public String getString() {
			return string;
		}

		public void setString(String string) {
			this.string = string;
		}

		@Override
		public String toString() {
			return id + ": " + string;
		}

		@Override
		public boolean equals(Object o) {
			if ( this == o ) {
				return true;
			}
			if ( o == null || getClass() != o.getClass() ) {
				return false;
			}
			TableId tableId = (TableId) o;
			return Objects.equals(string, tableId.string);
		}

		@Override
		public int hashCode() {
			return Objects.hash(string);
		}
	}
}
