package agency.encrypt.glrenderer.ecs

import kotlin.reflect.KClass

/** Marker for plain data attached to an entity. Components hold state; systems hold behaviour. */
interface Component

class Entity internal constructor(val id: Int) {
    @PublishedApi
    internal val components = HashMap<KClass<out Component>, Component>()

    fun add(component: Component): Entity {
        components[component::class] = component
        return this
    }

    inline fun <reified T : Component> get(): T? = components[T::class] as T?

    inline fun <reified T : Component> has(): Boolean = T::class in components

    inline fun <reified T : Component> remove() {
        components.remove(T::class)
    }

    override fun toString() = "Entity($id)"
}

fun interface System {
    fun update(world: World, deltaSeconds: Float)
}

class World {
    private var nextId = 0
    private val entityMap = LinkedHashMap<Int, Entity>()
    private val systems = mutableListOf<System>()

    val entities: Collection<Entity> get() = entityMap.values

    fun createEntity(): Entity = Entity(nextId++).also { entityMap[it.id] = it }

    fun entity(id: Int): Entity? = entityMap[id]

    fun destroy(entity: Entity) {
        entityMap.remove(entity.id)
    }

    fun addSystem(system: System) {
        systems += system
    }

    /** Runs every system once, in the order they were added. */
    fun update(deltaSeconds: Float) {
        systems.forEach { it.update(this, deltaSeconds) }
    }

    // Queries snapshot the matching entities first, so systems may add or remove components while iterating.

    inline fun <reified A : Component> each(action: (Entity, A) -> Unit) {
        entities.filter { it.has<A>() }.forEach { action(it, it.get<A>()!!) }
    }

    inline fun <reified A : Component, reified B : Component> each(action: (Entity, A, B) -> Unit) {
        entities.filter { it.has<A>() && it.has<B>() }.forEach { action(it, it.get<A>()!!, it.get<B>()!!) }
    }

    inline fun <reified A : Component, reified B : Component, reified C : Component> each(action: (Entity, A, B, C) -> Unit) {
        entities.filter { it.has<A>() && it.has<B>() && it.has<C>() }
            .forEach { action(it, it.get<A>()!!, it.get<B>()!!, it.get<C>()!!) }
    }
}
