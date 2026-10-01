package me.calo.islands;

import me.calo.islands.content.*;
import me.calo.islands.data.BossRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BossServiceTest {
    @Test void validatesMythicTemplateTransitionsOnceAndCleansCompletedEntity() throws Exception {
        BossRepository repository=mock(BossRepository.class);
        BossService.EntityAdapter adapter=mock(BossService.EntityAdapter.class);
        BossDefinition definition=new BossDefinition("technical_boss","MythicTemplate",List.of(
                new BossDefinition.Phase("opening",1),new BossDefinition.Phase("final",0.5)));
        UUID fightId=UUID.randomUUID(), entityId=UUID.randomUUID(), worldId=UUID.randomUUID();
        when(adapter.state(worldId,entityId)).thenReturn(BossService.EntityState.ALIVE);
        when(adapter.templateId(worldId,entityId)).thenReturn("WrongTemplate","MythicTemplate");
        AtomicInteger signals=new AtomicInteger();
        List<BossSignal.Type> types=new ArrayList<>();
        BossService service=new BossService(repository,adapter,signal->{
            signals.incrementAndGet(); types.add(signal.type());
            assertEquals(BossSignal.VERSION,signal.protocolVersion());
        },()->true);
        assertThrows(IllegalArgumentException.class,()->service.bind(fightId,entityId,worldId,definition));
        BossFight initial=service.bind(fightId,entityId,worldId,definition);
        UUID player=UUID.randomUUID(); service.join(fightId,player);
        verify(repository).join(fightId,player);
        BossAction action=new BossAction(UUID.randomUUID(),player,BossAction.Kind.DAMAGE,10,0.4);
        BossFight phase=initial.advance(0.4);
        when(repository.record(fightId,action)).thenReturn(new BossRepository.Result(phase,false,true,false),
                new BossRepository.Result(phase,true,false,false));
        service.confirmed(fightId,action); service.confirmed(fightId,action);
        verify(adapter, times(1)).phase(phase,definition.phases().get(1));
        assertEquals(2,signals.get());
        BossAction lethal=new BossAction(UUID.randomUUID(),player,BossAction.Kind.DAMAGE,30,0.0);
        BossFight completed=phase.advance(0);
        when(repository.record(fightId,lethal)).thenReturn(new BossRepository.Result(completed,false,false,true));
        service.confirmed(fightId,lethal);
        verify(adapter).cleanup(completed);
        assertEquals(List.of(BossSignal.Type.STARTED,BossSignal.Type.PHASE,
                BossSignal.Type.COMPLETED),types);
        service.shutdown();
        verify(adapter,times(1)).cleanup(completed);
    }

    @Test void unloadedEntitySurvivesRecoveryButDespawnTerminatesWithoutReward() throws Exception {
        BossRepository repository=mock(BossRepository.class);
        BossService.EntityAdapter adapter=mock(BossService.EntityAdapter.class);
        BossDefinition definition=new BossDefinition("technical_boss","MythicTemplate",List.of(
                new BossDefinition.Phase("opening",1)));
        BossFight fight=new BossFight(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                definition,BossFight.State.ACTIVE,0,1,1);
        when(repository.active()).thenReturn(List.of(fight));
        when(adapter.state(fight.worldId(),fight.entityId())).thenReturn(
                BossService.EntityState.UNLOADED,BossService.EntityState.DEAD);
        when(repository.terminate(fight.fightId(),BossFight.State.DESPAWNED)).thenReturn(
                fight.terminate(BossFight.State.DESPAWNED));
        BossService service=new BossService(repository,adapter,ignored->{},()->true);
        service.recover(); verify(repository,never()).terminate(any(),any());
        service.reconcile();
        verify(repository).terminate(fight.fightId(),BossFight.State.DESPAWNED);
        verify(adapter).cleanup(any());
    }

    @Test void recoveryRestoresCommittedPhaseAndRejectsReplacedEntity() throws Exception {
        BossRepository repository=mock(BossRepository.class);
        BossService.EntityAdapter adapter=mock(BossService.EntityAdapter.class);
        BossDefinition definition=new BossDefinition("technical_boss","MythicTemplate",List.of(
                new BossDefinition.Phase("opening",1),new BossDefinition.Phase("final",0.5)));
        BossFight valid=new BossFight(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                definition,BossFight.State.ACTIVE,1,0.3,4);
        BossFight replaced=new BossFight(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                definition,BossFight.State.ACTIVE,0,1,1);
        when(repository.active()).thenReturn(List.of(valid,replaced));
        when(adapter.state(any(),any())).thenReturn(BossService.EntityState.ALIVE);
        when(adapter.templateId(valid.worldId(),valid.entityId())).thenReturn("MythicTemplate");
        when(adapter.templateId(replaced.worldId(),replaced.entityId())).thenReturn("WrongTemplate");
        BossFight terminated=replaced.terminate(BossFight.State.DESPAWNED);
        when(repository.terminate(replaced.fightId(),BossFight.State.DESPAWNED)).thenReturn(terminated);
        new BossService(repository,adapter,ignored->{},()->true).recover();
        verify(adapter).phase(valid,definition.phases().get(1));
        verify(repository).terminate(replaced.fightId(),BossFight.State.DESPAWNED);
        verify(adapter).cleanup(terminated);
    }

    @Test void reconcileRejectsEntityReplacedAfterBinding() throws Exception {
        BossRepository repository = mock(BossRepository.class);
        BossService.EntityAdapter adapter = mock(BossService.EntityAdapter.class);
        BossDefinition definition = new BossDefinition("technical_boss", "MythicTemplate", List.of(
                new BossDefinition.Phase("opening", 1)));
        UUID fightId = UUID.randomUUID(), entityId = UUID.randomUUID(), worldId = UUID.randomUUID();
        BossFight active = new BossFight(fightId, entityId, worldId, definition,
                BossFight.State.ACTIVE, 0, 1, 1);
        BossFight despawned = active.terminate(BossFight.State.DESPAWNED);
        when(adapter.state(worldId, entityId)).thenReturn(BossService.EntityState.ALIVE);
        when(adapter.templateId(worldId, entityId)).thenReturn("MythicTemplate", "Replacement");
        when(repository.terminate(fightId, BossFight.State.DESPAWNED)).thenReturn(despawned);
        List<BossSignal.Type> signals = new ArrayList<>();
        BossService service = new BossService(repository, adapter,
                signal -> signals.add(signal.type()), () -> true);

        service.bind(fightId, entityId, worldId, definition);
        service.reconcile();

        verify(repository).terminate(fightId, BossFight.State.DESPAWNED);
        verify(adapter).cleanup(despawned);
        assertEquals(List.of(BossSignal.Type.STARTED, BossSignal.Type.DESPAWNED), signals);
    }
}
